export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
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
    throw new ApiError(payload.message ?? 'The task list could not be loaded.', response.status)
  }

  return Array.isArray(payload.tasks) ? payload.tasks.map(normalizeTask) : []
}

export async function requestRetry(_request) {
  // TODO(candidate): implement the published retry endpoint contract here.
  // The backend, not this browser client, remains authoritative for tenant identity.
  throw new Error('Retry API integration has not been implemented yet.')
}
