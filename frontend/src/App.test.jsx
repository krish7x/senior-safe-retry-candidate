import { render, screen, waitFor, within } from '@testing-library/react'
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

const retryableV0 = {
  id: 'task-alpha-retryable',
  workflowId: 'workflow-alpha',
  title: 'Submit invoice to gateway',
  status: 'FAILED_RETRYABLE',
  version: 0
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
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('retry-abc-12345678')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('posts expectedVersion with a fresh Idempotency-Key and bearer token', async () => {
    fetch
      .mockReturnValueOnce(jsonResponse({ tasks: [failedTask, completedTask] }))
      .mockReturnValueOnce(jsonResponse({
        id: 'task-17',
        workflowId: 'workflow-4',
        title: 'Transmit document',
        status: 'RETRY_QUEUED',
        version: 8,
        attemptId: 'attempt-1',
        replayed: false
      }, 202))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)
    await user.click(await screen.findByRole('button', { name: /Transmit document/i }))
    await user.click(screen.getByRole('button', { name: 'Retry task' }))

    await waitFor(() => {
      expect(fetch).toHaveBeenCalledWith(
        '/api/workflows/workflow-4/tasks/task-17/retry',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({
            Authorization: 'Bearer tenant-alpha-token',
            'Idempotency-Key': 'retry-abc-12345678'
          }),
          body: JSON.stringify({ expectedVersion: 7 })
        })
      )
    })
    expect(fetch.mock.calls[1][1].headers).not.toHaveProperty('X-Tenant-Id')
  })

  it('disables Retry while its request is pending and prevents duplicate clicks', async () => {
    let resolveRetry
    fetch
      .mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveRetry = resolve
      }))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)
    await user.click(await screen.findByRole('button', { name: /Transmit document/i }))

    const retryButton = screen.getByRole('button', { name: 'Retry task' })
    await user.click(retryButton)
    await waitFor(() => expect(retryButton).toBeDisabled())

    await user.click(retryButton)
    expect(fetch.mock.calls.filter((call) => String(call[0]).includes('/retry'))).toHaveLength(1)

    resolveRetry(new Response(JSON.stringify({
      id: 'task-17',
      workflowId: 'workflow-4',
      title: 'Transmit document',
      status: 'RETRY_QUEUED',
      version: 8,
      attemptId: 'attempt-1',
      replayed: false
    }), { status: 202, headers: { 'Content-Type': 'application/json' } }))

    await waitFor(() => expect(screen.getByText('Retry queued.')).toBeVisible())
  })

  it('updates the task in place after a 200 replay or 202 accepted response', async () => {
    fetch
      .mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
      .mockReturnValueOnce(jsonResponse({
        id: 'task-17',
        workflowId: 'workflow-4',
        title: 'Transmit document',
        status: 'RETRY_QUEUED',
        version: 8,
        attemptId: 'attempt-1',
        replayed: false
      }, 202))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)
    await user.click(await screen.findByRole('button', { name: /Transmit document/i }))
    await user.click(screen.getByRole('button', { name: 'Retry task' }))

    const detail = screen.getByRole('region', { name: 'Task details' })
    await waitFor(() => expect(within(detail).getByText('Version 8')).toBeVisible())
    expect(within(detail).getByText('Retry Queued')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Retry task' })).not.toBeInTheDocument()
  })

  it('presents a 409 conflict without hiding the Retry action', async () => {
    fetch
      .mockReturnValueOnce(jsonResponse({ tasks: [failedTask] }))
      .mockReturnValueOnce(jsonResponse({
        status: 409,
        code: 'STALE_TASK_VERSION',
        message: 'Expected task version 7 but current version is 9'
      }, 409))

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)
    await user.click(await screen.findByRole('button', { name: /Transmit document/i }))
    await user.click(screen.getByRole('button', { name: 'Retry task' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Expected task version 7')
    expect(screen.getByRole('button', { name: 'Retry task' })).toBeEnabled()
  })

  it('does not let an older response overwrite a newer task version', async () => {
    let resolveStaleList
    fetch.mockImplementation((url) => {
      if (url === '/api/tasks') {
        if (fetch.mock.calls.filter((call) => call[0] === '/api/tasks').length === 1) {
          return jsonResponse({ tasks: [retryableV0] })
        }
        return new Promise((resolve) => {
          resolveStaleList = resolve
        })
      }
      return jsonResponse({
        id: 'task-alpha-retryable',
        workflowId: 'workflow-alpha',
        title: 'Submit invoice to gateway',
        status: 'RETRY_QUEUED',
        version: 1,
        attemptId: 'attempt-9',
        replayed: false
      }, 202)
    })

    const user = userEvent.setup()
    render(<App authToken="tenant-alpha-token" />)
    await screen.findByRole('button', { name: /Submit invoice to gateway/i })

    await user.click(screen.getByRole('button', { name: 'Refresh tasks' }))
    await user.click(screen.getByRole('button', { name: 'Retry task' }))

    await waitFor(() => expect(screen.getByText('Version 1')).toBeVisible())

    resolveStaleList(new Response(JSON.stringify({ tasks: [retryableV0] }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh tasks' })).toBeEnabled())
    expect(screen.getByText('Version 1')).toBeVisible()
    expect(screen.queryByText('Version 0')).not.toBeInTheDocument()
  })
})
