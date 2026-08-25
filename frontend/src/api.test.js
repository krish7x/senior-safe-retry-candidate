import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchTasks } from './api.js'

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
