import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchTasks, mergeTasksPreferringNewer, requestRetry } from './api.js'

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

  it('posts the published retry contract without a client-supplied tenant header', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        id: 'task-alpha-retryable',
        workflowId: 'workflow-alpha',
        title: 'Submit invoice to gateway',
        status: 'RETRY_QUEUED',
        version: 1,
        attemptId: 'attempt-1',
        replayed: false
      }), {
        status: 202,
        headers: { 'Content-Type': 'application/json' }
      })
    ))

    await expect(requestRetry({
      authToken: 'tenant-alpha-token',
      task: {
        taskId: 'task-alpha-retryable',
        workflowId: 'workflow-alpha',
        version: 0
      },
      idempotencyKey: 'retry-abc-123'
    })).resolves.toMatchObject({
      taskId: 'task-alpha-retryable',
      state: 'RETRY_QUEUED',
      version: 1
    })

    expect(fetch).toHaveBeenCalledWith(
      '/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer tenant-alpha-token',
          'Idempotency-Key': 'retry-abc-123'
        }),
        body: JSON.stringify({ expectedVersion: 0 })
      })
    )
    expect(fetch.mock.calls[0][1].headers).not.toHaveProperty('X-Tenant-Id')
  })

  it('keeps a newer local task version when an older list payload arrives', () => {
    expect(mergeTasksPreferringNewer(
      [{ taskId: 'task-alpha-retryable', version: 1, state: 'RETRY_QUEUED' }],
      [{ taskId: 'task-alpha-retryable', version: 0, state: 'FAILED_RETRYABLE' }]
    )).toEqual([{ taskId: 'task-alpha-retryable', version: 1, state: 'RETRY_QUEUED' }])
  })
})
