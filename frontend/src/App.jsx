import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchTasks, requestRetry } from './api.js'
import './styles.css'

const RETRYABLE_STATE = 'FAILED_RETRYABLE'

function taskKey(task) {
  return `${task.workflowId}:${task.taskId}`
}

function humanize(value) {
  return value
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

function StateBadge({ state }) {
  return <span className={`state-badge state-${state.toLowerCase()}`}>{humanize(state)}</span>
}

function TaskList({ tasks, selectedKey, onSelect }) {
  if (tasks.length === 0) {
    return (
      <div className="empty-state">
        <span className="empty-state__mark" aria-hidden="true">✓</span>
        <h2>No tasks found</h2>
        <p>This authenticated tenant has no task activity to review.</p>
      </div>
    )
  }

  return (
    <ul className="task-list" aria-label="Tasks">
      {tasks.map((task) => {
        const key = taskKey(task)
        const selected = key === selectedKey
        return (
          <li key={key}>
            <button
              type="button"
              className={`task-row${selected ? ' task-row--selected' : ''}`}
              aria-pressed={selected}
              aria-label={`${task.name}, ${humanize(task.state)}`}
              onClick={() => onSelect(key)}
            >
              <span className="task-row__main">
                <strong>{task.name}</strong>
                <small>{task.workflowId} · {task.taskId}</small>
              </span>
              <span className="task-row__meta">
                <StateBadge state={task.state} />
                <small>v{task.version}</small>
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

function ErrorNotice({ error, onRetry }) {
  return (
    <div className="notice notice--error" role="alert">
      <div>
        <strong>Something needs attention</strong>
        <p>{error.message}</p>
      </div>
      {onRetry ? (
        <button type="button" className="button button--secondary" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  )
}

function TaskDetail({ task, onRetry }) {
  if (!task) {
    return (
      <section className="detail-panel detail-panel--empty" aria-label="Task details">
        <p>Select a task to inspect its retry state.</p>
      </section>
    )
  }

  return (
    <section className="detail-panel" aria-label="Task details">
      <div className="detail-panel__heading">
        <div>
          <p className="eyebrow">Task details</p>
          <h2>{task.name}</h2>
        </div>
        <StateBadge state={task.state} />
      </div>

      <dl className="facts">
        <div>
          <dt>Workflow</dt>
          <dd>{task.workflowId}</dd>
        </div>
        <div>
          <dt>Task ID</dt>
          <dd>{task.taskId}</dd>
        </div>
        <div>
          <dt>State version</dt>
          <dd>Version {task.version}</dd>
        </div>
        <div>
          <dt>Attempts</dt>
          <dd>{task.attemptCount}</dd>
        </div>
      </dl>

      {task.lastError ? (
        <div className="failure-card">
          <span>Last failure</span>
          <p>{task.lastError}</p>
        </div>
      ) : null}

      <div className="detail-panel__actions">
        {task.state === RETRYABLE_STATE ? (
          <button type="button" className="button button--primary" onClick={() => onRetry(task)}>
            Retry task
          </button>
        ) : (
          <p className="muted">This task is not eligible for retry.</p>
        )}
      </div>
    </section>
  )
}

export default function App({ authToken = import.meta.env.VITE_DEMO_AUTH_TOKEN ?? 'tenant-alpha-token' }) {
  const [tasks, setTasks] = useState([])
  const [selectedKey, setSelectedKey] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [retryError, setRetryError] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError(null)

    try {
      const loadedTasks = await fetchTasks(authToken)
      setTasks(loadedTasks)
      setSelectedKey((current) => {
        if (loadedTasks.some((task) => taskKey(task) === current)) return current
        return loadedTasks.length > 0 ? taskKey(loadedTasks[0]) : null
      })
    } catch (error) {
      setLoadError(error instanceof Error ? error : new Error('The task list could not be loaded.'))
    } finally {
      setLoading(false)
    }
  }, [authToken])

  useEffect(() => {
    load()
  }, [load])

  const selectedTask = useMemo(
    () => tasks.find((task) => taskKey(task) === selectedKey) ?? null,
    [selectedKey, tasks]
  )

  const retry = useCallback(async (task) => {
    setRetryError(null)

    try {
      await requestRetry({ authToken, task })

      // TODO(candidate): update the task without reloading after a 200 or 202 response.
      // Preserve the newest task version when requests complete out of order.
    } catch (error) {
      // TODO(candidate): distinguish a 409 conflict from other request failures.
      setRetryError(error instanceof Error ? error : new Error('The retry could not be queued.'))
    }

    // TODO(candidate): expose pending state and prevent duplicate clicks while awaiting the request.
  }, [authToken])

  const failedCount = tasks.filter((task) => task.state === RETRYABLE_STATE).length

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">R</span>
          <div>
            <strong>Retry Control Room</strong>
            <small>Workflow operations</small>
          </div>
        </div>
        <div className="tenant-pill" aria-label="Authenticated operator session">
          <span aria-hidden="true" />
          Authenticated
        </div>
      </header>

      <main>
        <div className="page-heading">
          <div>
            <p className="eyebrow">Operations dashboard</p>
            <h1>Task recovery</h1>
            <p>Review failed workflow tasks and queue safe, traceable retries.</p>
          </div>
          <button type="button" className="button button--secondary" onClick={load} disabled={loading}>
            Refresh tasks
          </button>
        </div>

        <div className="summary-grid" aria-label="Task summary">
          <div><span>Total tasks</span><strong>{tasks.length}</strong></div>
          <div><span>Retryable</span><strong>{failedCount}</strong></div>
          <div><span>Tenant boundary</span><strong>Server-enforced</strong></div>
        </div>

        {loadError ? <ErrorNotice error={loadError} onRetry={load} /> : null}
        {retryError ? <ErrorNotice error={retryError} /> : null}

        {loading && tasks.length === 0 ? (
          <div className="loading-state" role="status">
            <span className="spinner" aria-hidden="true" />
            Loading tasks…
          </div>
        ) : (
          <div className="workspace-grid">
            <section className="list-panel" aria-label="Task list">
              <div className="panel-heading">
                <div>
                  <p className="eyebrow">Queue</p>
                  <h2>Recent tasks</h2>
                </div>
                <span>{tasks.length}</span>
              </div>
              <TaskList tasks={tasks} selectedKey={selectedKey} onSelect={setSelectedKey} />
            </section>
            <TaskDetail task={selectedTask} onRetry={retry} />
          </div>
        )}
      </main>
    </div>
  )
}
