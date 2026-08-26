import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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

/**
 * Version-guarded merge. A response that carries an older task version than the
 * copy already on screen is discarded, so a slow task-list response cannot
 * overwrite the newer state a retry response already applied.
 */
function mergeTask(existing, incoming) {
  if (!existing) return incoming
  if (existing.version > incoming.version) return existing
  return { ...existing, ...incoming }
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

function ConflictNotice({ conflict, onRefresh }) {
  return (
    <div className="notice notice--conflict" role="alert">
      <div>
        <strong>This task changed somewhere else</strong>
        <p>{conflict.message}</p>
      </div>
      <button type="button" className="button button--secondary" onClick={onRefresh}>
        Refresh tasks
      </button>
    </div>
  )
}

function SuccessNotice({ success }) {
  return (
    <div className="notice notice--success" role="status">
      <div>
        <strong>{success.replayed ? 'Retry already queued' : 'Retry queued'}</strong>
        <p>
          {success.replayed
            ? 'The server replayed the original accepted attempt; no duplicate work was created.'
            : 'The task moved to Retry queued.'}
          {' '}Attempt {success.attemptId}.
        </p>
      </div>
    </div>
  )
}

function TaskDetail({ task, onRetry, pending }) {
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
          <>
            <button
              type="button"
              className="button button--primary"
              onClick={() => onRetry(task)}
              disabled={pending}
              aria-busy={pending}
            >
              Retry task
            </button>
            {pending ? (
              <p className="muted" role="status">Queueing retry…</p>
            ) : null}
          </>
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
  const [retryConflict, setRetryConflict] = useState(null)
  const [retrySuccess, setRetrySuccess] = useState(null)
  const [pendingKey, setPendingKey] = useState(null)

  // Synchronous guard: a second click lands before React has flushed `pendingKey`,
  // so the ref — not the rendered state — is what actually stops the second request.
  const inFlightRetries = useRef(new Set())
  // Monotonic list-request generation; only the newest list request owns `loading`.
  const listGeneration = useRef(0)

  const load = useCallback(async () => {
    const generation = (listGeneration.current += 1)
    setLoading(true)
    setLoadError(null)

    try {
      const loadedTasks = await fetchTasks(authToken)
      setTasks((current) => {
        const byKey = new Map(current.map((task) => [taskKey(task), task]))
        return loadedTasks.map((task) => mergeTask(byKey.get(taskKey(task)), task))
      })
      setSelectedKey((current) => {
        if (loadedTasks.some((task) => taskKey(task) === current)) return current
        return loadedTasks.length > 0 ? taskKey(loadedTasks[0]) : null
      })
    } catch (error) {
      if (generation !== listGeneration.current) return
      setLoadError(error instanceof Error ? error : new Error('The task list could not be loaded.'))
    } finally {
      if (generation === listGeneration.current) setLoading(false)
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
    const key = taskKey(task)
    if (inFlightRetries.current.has(key)) return
    inFlightRetries.current.add(key)
    setPendingKey(key)
    setRetryError(null)
    setRetryConflict(null)
    setRetrySuccess(null)

    try {
      // One click, one key: generated here and never reused for a later click.
      const outcome = await requestRetry({ authToken, task })
      setTasks((current) => current.map(
        (candidate) => (taskKey(candidate) === key ? mergeTask(candidate, outcome.task) : candidate)
      ))
      setRetrySuccess({ replayed: outcome.replayed, attemptId: outcome.attemptId })
    } catch (error) {
      if (error?.status === 409) {
        setRetryConflict(error)
      } else {
        setRetryError(error instanceof Error ? error : new Error('The retry could not be queued.'))
      }
    } finally {
      inFlightRetries.current.delete(key)
      setPendingKey((current) => (current === key ? null : current))
    }
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
        {retryConflict ? <ConflictNotice conflict={retryConflict} onRefresh={load} /> : null}
        {retryError ? <ErrorNotice error={retryError} /> : null}
        {retrySuccess ? <SuccessNotice success={retrySuccess} /> : null}

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
            <TaskDetail
              task={selectedTask}
              onRetry={retry}
              pending={selectedTask ? pendingKey === taskKey(selectedTask) : false}
            />
          </div>
        )}
      </main>
    </div>
  )
}
