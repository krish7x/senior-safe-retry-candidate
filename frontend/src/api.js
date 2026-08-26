export class ApiError extends Error {
  constructor(message, status, code) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }

  get isConflict() {
    return this.status === 409
  }
}

function normalizeTask(task) {
  return {
    taskId: task.taskId ?? task.id,
    workflowId: task.workflowId,
    name: task.name ?? task.title,
    state: task.state ?? task.status,
    version: Number(task.version ?? 0),
    attemptCount: Number(task.attemptCount ?? 0),
    ...(task.lastError ? { lastError: task.lastError } : {})
  }
}

async function readJson(response) {
  try {
    return await response.json()
  } catch {
    return {}
  }
}

/**
 * One logical retry action gets exactly one key. The server contract is
 * `[A-Za-z0-9][A-Za-z0-9._:-]{7,119}`, so the prefix keeps the first character
 * alphanumeric and a UUID supplies the rest.
 */
export function newIdempotencyKey() {
  const random = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`
  return `retry-${random}`
}

export async function fetchTasks(authToken) {
  const response = await fetch('/api/tasks', {
    method: 'GET',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${authToken}`
    }
  })
  const payload = await response.json()

  if (!response.ok) {
    throw new ApiError(payload.message ?? 'The task list could not be loaded.', response.status, payload.code)
  }

  return Array.isArray(payload.tasks) ? payload.tasks.map(normalizeTask) : []
}

/**
 * Posts the published retry contract. The bearer token alone identifies the
 * tenant; no tenant identifier is ever sent in the URL, body, or a header.
 */
export async function requestRetry({ authToken, task, idempotencyKey = newIdempotencyKey() }) {
  const path = `/api/workflows/${encodeURIComponent(task.workflowId)}`
    + `/tasks/${encodeURIComponent(task.taskId)}/retry`

  const response = await fetch(path, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      Authorization: `Bearer ${authToken}`,
      'Idempotency-Key': idempotencyKey
    },
    body: JSON.stringify({ expectedVersion: task.version })
  })
  const payload = await readJson(response)

  if (!response.ok) {
    throw new ApiError(payload.message ?? 'The retry could not be queued.', response.status, payload.code)
  }

  return {
    idempotencyKey,
    attemptId: payload.attemptId,
    replayed: Boolean(payload.replayed),
    // Only server-authoritative fields; the caller merges them over its own copy.
    task: {
      taskId: payload.id,
      workflowId: payload.workflowId,
      name: payload.title,
      state: payload.status,
      version: Number(payload.version)
    }
  }
}
