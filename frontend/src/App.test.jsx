import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.jsx'

const failedTask = {
  taskId: 'task-17',
  workflowId: 'workflow-4',
  name: 'Transmit document',
  state: 'FAILED_RETRYABLE',
  version: 7,
  attemptCount: 2,
  lastError: 'Partner gateway timed out'
}

const completedTask = {
  taskId: 'task-18',
  workflowId: 'workflow-4',
  name: 'Validate payload',
  state: 'SUCCEEDED',
  version: 3,
  attemptCount: 1
}

function jsonResponse(body, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' }
    })
  )
}

describe('provided Retry Control Room shell', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads tasks with the supplied authentication context', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [failedTask, completedTask] }))

    render(<App authToken="tenant-alpha-token" />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading tasks')
    expect(fetch).toHaveBeenCalledWith(
      '/api/tasks',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer tenant-alpha-token' })
      })
    )
    expect(fetch.mock.calls[0][1].headers).not.toHaveProperty('X-Tenant-Id')
    expect(await screen.findByRole('button', { name: /Transmit document/i })).toBeVisible()
  })

  it('lets an operator select a task and inspect the supplied details', async () => {
    fetch.mockReturnValueOnce(jsonResponse({ tasks: [completedTask, failedTask] }))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    await user.click(await screen.findByRole('button', { name: /Transmit document/i }))

    const detail = screen.getByRole('region', { name: 'Task details' })
    expect(within(detail).getByText('Partner gateway timed out')).toBeVisible()
    expect(within(detail).getByText('Version 7')).toBeVisible()
    expect(within(detail).getByRole('button', { name: 'Retry task' })).toBeEnabled()
  })

  it('shows an empty state after recovering from a list failure', async () => {
    fetch
      .mockReturnValueOnce(jsonResponse({ message: 'Database is unavailable' }, 503))
      .mockReturnValueOnce(jsonResponse({ tasks: [] }))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Database is unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('No tasks found')).toBeVisible()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})

describe('public retry contract — candidate implementation required', () => {
  it.todo('posts expectedVersion with a fresh Idempotency-Key and bearer token')
  it.todo('disables Retry while its request is pending and prevents duplicate clicks')
  it.todo('updates the task in place after a 200 replay or 202 accepted response')
  it.todo('presents a 409 conflict without hiding the Retry action')
  it.todo('does not let an older response overwrite a newer task version')
})
