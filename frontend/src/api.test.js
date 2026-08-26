import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchTasks, newIdempotencyKey, requestRetry } from './api.js'

describe('provided task-list API adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('normalizes the published backend task shape for the supplied UI shell', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        tasks: [{
          id: 'task-alpha-retryable',
          workflowId: 'workflow-alpha',
          title: 'Submit invoice to gateway',
          status: 'FAILED_RETRYABLE',
          version: 0
        }]
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    ))

    await expect(fetchTasks('tenant-alpha-token')).resolves.toEqual([{
      taskId: 'task-alpha-retryable',
      workflowId: 'workflow-alpha',
      name: 'Submit invoice to gateway',
      state: 'FAILED_RETRYABLE',
      version: 0,
      attemptCount: 0
    }])
  })
})

describe('retry API adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const task = {
    taskId: 'task-alpha-retryable',
    workflowId: 'workflow-alpha',
    name: 'Submit invoice to gateway',
    state: 'FAILED_RETRYABLE',
    version: 0
  }

  function stubResponse(body, status) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' }
      })
    ))
  }

  it('generates keys that satisfy the published Idempotency-Key grammar', () => {
    const keys = new Set()
    for (let index = 0; index < 100; index += 1) {
      const key = newIdempotencyKey()
      expect(key).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$/)
      keys.add(key)
    }
    expect(keys.size).toBe(100)
  })

  it('returns only server-authoritative task fields from an accepted retry', async () => {
    stubResponse({
      id: 'task-alpha-retryable',
      workflowId: 'workflow-alpha',
      title: 'Submit invoice to gateway',
      status: 'RETRY_QUEUED',
      version: 1,
      attemptId: 'attempt-1',
      replayed: false
    }, 202)

    await expect(requestRetry({ authToken: 'tenant-alpha-token', task, idempotencyKey: 'retry-abc-123' }))
      .resolves.toEqual({
        idempotencyKey: 'retry-abc-123',
        attemptId: 'attempt-1',
        replayed: false,
        task: {
          taskId: 'task-alpha-retryable',
          workflowId: 'workflow-alpha',
          name: 'Submit invoice to gateway',
          state: 'RETRY_QUEUED',
          version: 1
        }
      })
  })

  it('raises a typed ApiError carrying the published conflict code', async () => {
    stubResponse({
      status: 409,
      code: 'IDEMPOTENCY_KEY_REUSED',
      message: 'Idempotency key was already used for a different request'
    }, 409)

    const failure = await requestRetry({ authToken: 'tenant-alpha-token', task })
      .catch((error) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure.status).toBe(409)
    expect(failure.code).toBe('IDEMPOTENCY_KEY_REUSED')
    expect(failure.isConflict).toBe(true)
  })

  it('falls back to a safe message when an error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>502</html>', { status: 502 })))

    const failure = await requestRetry({ authToken: 'tenant-alpha-token', task })
      .catch((error) => error)

    expect(failure.status).toBe(502)
    expect(failure.message).toBe('The retry could not be queued.')
  })
})
