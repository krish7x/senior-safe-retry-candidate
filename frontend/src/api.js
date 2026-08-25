export class ApiError extends Error {
  constructor(message, status, code) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
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
    ...(task.lastError ? { lastError: task.lastError } : {}),
    ...(task.attemptId ? { attemptId: task.attemptId } : {}),
    ...(typeof task.replayed === 'boolean' ? { replayed: task.replayed } : {})
  }
}

export function createIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID()
  }
  return `key-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function mergeTasksPreferringNewer(current, incoming) {
  const currentById = new Map(current.map((task) => [task.taskId, task]))
  return incoming.map((task) => {
    const existing = currentById.get(task.taskId)
    if (existing && existing.version > task.version) {
      return existing
    }
    return task
  })
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

export async function requestRetry({ authToken, task, idempotencyKey }) {
  const response = await fetch(`/api/workflows/${task.workflowId}/tasks/${task.taskId}/retry`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${authToken}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey
    },
    body: JSON.stringify({ expectedVersion: task.version })
  })
  const payload = await response.json().catch(() => ({}))

  if (!response.ok) {
    throw new ApiError(
      payload.message ?? 'The retry could not be queued.',
      response.status,
      payload.code
    )
  }

  return normalizeTask(payload)
}
