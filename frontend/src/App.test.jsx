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


const retryableAlpha = {
  taskId: 'task-alpha-retryable',
  workflowId: 'workflow-alpha',
  name: 'Submit invoice to gateway',
  state: 'FAILED_RETRYABLE',
  version: 0,
  attemptCount: 0
}

/** The published accepted/replay response shape. */
function retryResponse({ version = 1, replayed = false, status = 'RETRY_QUEUED' } = {}) {
  return {
    id: retryableAlpha.taskId,
    workflowId: retryableAlpha.workflowId,
    title: retryableAlpha.name,
    status,
    version,
    attemptId: 'attempt-0001',
    replayed
  }
}

function deferred() {
  let settle
  const promise = new Promise((resolve) => {
    settle = resolve
  })
  return { promise, settle }
}

async function renderWithTask(task = retryableAlpha) {
  fetch.mockReturnValueOnce(jsonResponse({ tasks: [task] }))
  const user = userEvent.setup()
  render(<App authToken="tenant-alpha-token" />)
  await screen.findByRole('button', { name: new RegExp(task.name, 'i') })
  return user
}

function retryButton() {
  return within(screen.getByRole('region', { name: 'Task details' }))
    .getByRole('button', { name: 'Retry task' })
}

describe('public retry contract', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts expectedVersion with a fresh Idempotency-Key and bearer token', async () => {
    const user = await renderWithTask()
    fetch.mockReturnValueOnce(jsonResponse(retryResponse(), 202))

    await user.click(retryButton())

    const [url, init] = fetch.mock.calls[1]
    expect(url).toBe('/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry')
    expect(init.method).toBe('POST')
    expect(init.headers.Authorization).toBe('Bearer tenant-alpha-token')
    expect(init.headers['Idempotency-Key']).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$/)
    expect(JSON.parse(init.body)).toEqual({ expectedVersion: 0 })
    // The browser never asserts a tenant; the bearer token is the only claim.
    expect(init.headers).not.toHaveProperty('X-Tenant-Id')
  })

  it('uses a new key for each deliberate retry action', async () => {
    const user = await renderWithTask()
    // The first action conflicts, so the task stays retryable and the operator clicks again.
    fetch.mockReturnValueOnce(jsonResponse({
      status: 409, code: 'STALE_TASK_VERSION', message: 'Task version moved on'
    }, 409))
    await user.click(retryButton())
    await screen.findByRole('alert')

    // A second deliberate click is a new logical action, so it must not replay the first key.
    fetch.mockReturnValueOnce(jsonResponse(retryResponse(), 202))
    await user.click(retryButton())

    expect(fetch.mock.calls[1][1].headers['Idempotency-Key'])
      .not.toBe(fetch.mock.calls[2][1].headers['Idempotency-Key'])
  })

  it('disables Retry while its request is pending and prevents duplicate clicks', async () => {
    const user = await renderWithTask()
    const pending = deferred()
    fetch.mockReturnValueOnce(pending.promise)

    await user.click(retryButton())

    expect(retryButton()).toBeDisabled()
    expect(retryButton()).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('Queueing retry…')).toBeVisible()

    await user.click(retryButton())
    await user.click(retryButton())
    expect(fetch).toHaveBeenCalledTimes(2) // one list load plus exactly one retry

    pending.settle(new Response(JSON.stringify(retryResponse()), {
      status: 202,
      headers: { 'Content-Type': 'application/json' }
    }))

    expect(await screen.findByText('Retry queued')).toBeVisible()
    // RETRY_QUEUED is no longer retryable, so the action is withdrawn rather than left enabled.
    expect(screen.getByText('This task is not eligible for retry.')).toBeVisible()
  })

  it('updates the task in place after a 202 accepted response', async () => {
    const user = await renderWithTask()
    fetch.mockReturnValueOnce(jsonResponse(retryResponse(), 202))

    await user.click(retryButton())

    const detail = await screen.findByRole('region', { name: 'Task details' })
    expect(within(detail).getByText('Version 1')).toBeVisible()
    expect(await screen.findByRole('button', { name: /Submit invoice to gateway, Retry Queued/i }))
      .toBeVisible()
    // No full reload: the task list was fetched exactly once.
    expect(fetch.mock.calls.filter(([url]) => url === '/api/tasks')).toHaveLength(1)
  })

  it('reports a 200 replay without creating a second attempt', async () => {
    const user = await renderWithTask()
    fetch.mockReturnValueOnce(jsonResponse(retryResponse({ replayed: true }), 200))

    await user.click(retryButton())

    expect(await screen.findByText('Retry already queued')).toBeVisible()
    expect(within(screen.getByRole('region', { name: 'Task details' })).getByText('Version 1'))
      .toBeVisible()
  })

  it('presents a 409 conflict without hiding the Retry action', async () => {
    const user = await renderWithTask()
    fetch.mockReturnValueOnce(jsonResponse({
      status: 409,
      code: 'STALE_TASK_VERSION',
      message: 'Expected task version 0 but current version is 1'
    }, 409))

    await user.click(retryButton())

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('Expected task version 0 but current version is 1')
    expect(retryButton()).toBeEnabled()
  })

  it('reports an unexpected server failure separately from a conflict', async () => {
    const user = await renderWithTask()
    fetch.mockReturnValueOnce(jsonResponse({
      status: 500,
      code: 'INTERNAL_ERROR',
      message: 'The request could not be completed'
    }, 500))

    await user.click(retryButton())

    expect(await screen.findByRole('alert')).toHaveTextContent('The request could not be completed')
    expect(screen.getByText('Something needs attention')).toBeVisible()
    expect(retryButton()).toBeEnabled()
  })

  it('does not let an older task-list response overwrite a newer task version', async () => {
    const user = await renderWithTask()

    // A list request starts first and is deliberately left in flight.
    const staleList = deferred()
    fetch.mockReturnValueOnce(staleList.promise)
    await user.click(screen.getByRole('button', { name: 'Refresh tasks' }))

    // The retry response lands first and moves the task to version 1.
    fetch.mockReturnValueOnce(jsonResponse(retryResponse(), 202))
    await user.click(retryButton())
    expect(within(screen.getByRole('region', { name: 'Task details' })).getByText('Version 1'))
      .toBeVisible()

    // The older list response now returns the pre-retry version 0 snapshot.
    staleList.settle(new Response(JSON.stringify({ tasks: [{
      id: retryableAlpha.taskId,
      workflowId: retryableAlpha.workflowId,
      title: retryableAlpha.name,
      status: 'FAILED_RETRYABLE',
      version: 0
    }] }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    // The in-flight load owns `loading`; it flipping back to false proves the stale
    // response was received and applied before these assertions run.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh tasks' })).toBeEnabled())

    const detail = screen.getByRole('region', { name: 'Task details' })
    expect(within(detail).getByText('Version 1')).toBeVisible()
    expect(within(detail).queryByText('Version 0')).toBeNull()
  })
})
