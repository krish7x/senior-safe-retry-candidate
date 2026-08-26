package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.AuditEventEntity;
import com.complyance.assignment.retry.domain.AuditEventRepository;
import com.complyance.assignment.retry.domain.IdempotencyRaceException;
import com.complyance.assignment.retry.domain.OutboxMessageEntity;
import com.complyance.assignment.retry.domain.OutboxMessageRepository;
import com.complyance.assignment.retry.domain.RetryAttemptEntity;
import com.complyance.assignment.retry.domain.RetryAttemptRepository;
import com.complyance.assignment.retry.domain.RetryConflictException;
import com.complyance.assignment.retry.domain.TaskEntity;
import com.complyance.assignment.retry.domain.TaskNotFoundException;
import com.complyance.assignment.retry.domain.TaskRepository;
import com.complyance.assignment.retry.domain.TaskStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The single database transaction that owns every retry effect.
 *
 * <p>This type is a separate bean from {@link RetryService} on purpose: the
 * lost-race path has to re-read committed state in a <em>new</em> transaction, and
 * a self-invocation inside one bean would silently reuse the failed one.
 */
@Component
class RetryTransaction {

    static final String AUDIT_EVENT_TYPE = "TASK_RETRY_QUEUED";
    static final String OUTBOX_EVENT_TYPE = "TASK_RETRY_REQUESTED";

    private final TaskRepository tasks;
    private final RetryAttemptRepository attempts;
    private final AuditEventRepository auditEvents;
    private final OutboxMessageRepository outboxMessages;
    private final RetryFailureInjector failureInjector;
    private final ObjectMapper objectMapper;

    RetryTransaction(
            TaskRepository tasks,
            RetryAttemptRepository attempts,
            AuditEventRepository auditEvents,
            OutboxMessageRepository outboxMessages,
            RetryFailureInjector failureInjector,
            ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.auditEvents = auditEvents;
        this.outboxMessages = outboxMessages;
        this.failureInjector = failureInjector;
        this.objectMapper = objectMapper;
    }

    /**
     * One transaction, seven effects. Either all of them commit or none of them do.
     */
    @Transactional
    RetryOutcome accept(RetryCommand command, String fingerprint) {
        // 1. Tenant-scoped idempotency lookup runs before the task is resolved, because a
        //    reused key must lose with 409 even when the new target would be a 404.
        var alreadyAccepted = findAttempt(command);
        if (alreadyAccepted != null) {
            return replayOrReject(alreadyAccepted, fingerprint);
        }

        // 2. Serialise every contender for this task on the task row itself.
        var task = tasks.lockForRetry(command.tenantId(), command.workflowId(), command.taskId())
                .orElseThrow(TaskNotFoundException::new);

        // 3. Re-read the key under the lock. A same-key contender that lost the race sees
        //    the winner's committed attempt here — never before it started waiting.
        var acceptedWhileWaiting = findAttempt(command);
        if (acceptedWhileWaiting != null) {
            return replayOrReject(acceptedWhileWaiting, fingerprint);
        }

        if (task.getVersion() != command.expectedVersion()) {
            throw RetryConflictException.staleVersion(command.expectedVersion(), task.getVersion());
        }
        if (task.getStatus() != TaskStatus.FAILED_RETRYABLE) {
            throw RetryConflictException.notRetryable(task.getStatus());
        }

        return writeAcceptedRetry(command, fingerprint, task);
    }

    /**
     * Re-resolves a contender that PostgreSQL rejected at commit time, in a fresh
     * transaction, after the winning transaction has committed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    RetryOutcome resolveLostRace(RetryCommand command, String fingerprint) {
        var winner = findAttempt(command);
        if (winner != null) {
            return replayOrReject(winner, fingerprint);
        }
        // Our key is still free, so the constraint that rejected us was the
        // one-accepted-attempt-per-task-version guard: a different key won this version.
        var task = tasks.findByIdAndTenantId(command.taskId(), command.tenantId())
                .orElseThrow(TaskNotFoundException::new);
        throw RetryConflictException.staleVersion(command.expectedVersion(), task.getVersion());
    }

    private RetryOutcome writeAcceptedRetry(RetryCommand command, String fingerprint, TaskEntity task) {
        var now = Instant.now();
        var attemptId = UUID.randomUUID().toString();

        // 3. + 4. The status change and the version increment are one conditional UPDATE:
        //    Hibernate's @Version adds `where version = :expected`, so even without the row
        //    lock the database, not Java, decides whether this transition may happen.
        task.queueRetry(now);
        tasks.flush();

        var attempt = new RetryAttemptEntity(
                attemptId,
                command.tenantId(),
                command.workflowId(),
                task.getId(),
                task.getTitle(),
                task.getStatus(),
                task.getVersion(),
                command.idempotencyKey(),
                fingerprint,
                now);

        try {
            // 5. + 7. The attempt row is both the retry record and the idempotency result.
            //    uq_retry_attempts_tenant_key and uq_retry_attempts_task_version are the
            //    cross-process arbiters; each save is flushed so the failing statement is
            //    the one that violates its constraint.
            attempts.saveAndFlush(attempt);

            // 6. Audit event, then outbox record. Both carry the attempt's foreign key, so
            //    the insert order is also the order the database requires.
            auditEvents.saveAndFlush(new AuditEventEntity(
                    UUID.randomUUID().toString(),
                    command.tenantId(),
                    task.getId(),
                    attemptId,
                    AUDIT_EVENT_TYPE,
                    now));

            outboxMessages.saveAndFlush(new OutboxMessageEntity(
                    UUID.randomUUID().toString(),
                    command.tenantId(),
                    task.getId(),
                    attemptId,
                    OUTBOX_EVENT_TYPE,
                    outboxPayload(command, task, attemptId),
                    now));
        } catch (DataIntegrityViolationException rejected) {
            throw new IdempotencyRaceException(rejected);
        }

        // The published fault-injection point. Anything thrown here unwinds all six
        // preceding effects, because they all belong to this transaction.
        failureInjector.afterOutboxInserted();

        return new RetryOutcome(
                task.getId(),
                task.getWorkflowId(),
                task.getTitle(),
                task.getStatus(),
                task.getVersion(),
                attemptId,
                false);
    }

    private RetryAttemptEntity findAttempt(RetryCommand command) {
        return attempts
                .findByTenantIdAndIdempotencyKey(command.tenantId(), command.idempotencyKey())
                .orElse(null);
    }

    /**
     * An exact replay returns the originally accepted values. Any different fingerprint
     * field — including one whose new target does not exist — is a reuse conflict.
     */
    private RetryOutcome replayOrReject(RetryAttemptEntity attempt, String fingerprint) {
        if (!attempt.getRequestFingerprint().equals(fingerprint)) {
            throw RetryConflictException.reusedKey();
        }
        return new RetryOutcome(
                attempt.getTaskId(),
                attempt.getWorkflowId(),
                attempt.getTaskTitle(),
                attempt.getAcceptedStatus(),
                attempt.getAcceptedVersion(),
                attempt.getId(),
                true);
    }

    private String outboxPayload(RetryCommand command, TaskEntity task, String attemptId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventType", OUTBOX_EVENT_TYPE);
        payload.put("tenantId", command.tenantId());
        payload.put("workflowId", task.getWorkflowId());
        payload.put("taskId", task.getId());
        payload.put("attemptId", attemptId);
        payload.put("acceptedVersion", task.getVersion());
        return objectMapper.writeValueAsString(payload);
    }
}
