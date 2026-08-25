package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.AssignmentException;
import com.complyance.assignment.retry.domain.AuditEventEntity;
import com.complyance.assignment.retry.domain.AuditEventRepository;
import com.complyance.assignment.retry.domain.IdempotencyRecordEntity;
import com.complyance.assignment.retry.domain.IdempotencyRecordRepository;
import com.complyance.assignment.retry.domain.InvalidRetryRequestException;
import com.complyance.assignment.retry.domain.OutboxMessageEntity;
import com.complyance.assignment.retry.domain.OutboxMessageRepository;
import com.complyance.assignment.retry.domain.RetryAttemptEntity;
import com.complyance.assignment.retry.domain.RetryAttemptRepository;
import com.complyance.assignment.retry.domain.RetryConflictException;
import com.complyance.assignment.retry.domain.TaskEntity;
import com.complyance.assignment.retry.domain.TaskNotFoundException;
import com.complyance.assignment.retry.domain.TaskRepository;
import com.complyance.assignment.retry.domain.TaskStatus;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetryService {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$");
    private static final int UNIQUE_VIOLATION_RETRIES = 8;

    private final TaskRepository taskRepository;
    private final RetryAttemptRepository retryAttemptRepository;
    private final AuditEventRepository auditEventRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final RetryFailureInjector failureInjector;
    private final RetryInterleaveHook interleaveHook;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final boolean postgresAdvisoryLocks;

    public RetryService(
            TaskRepository taskRepository,
            RetryAttemptRepository retryAttemptRepository,
            AuditEventRepository auditEventRepository,
            OutboxMessageRepository outboxMessageRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            RetryFailureInjector failureInjector,
            RetryInterleaveHook interleaveHook,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.taskRepository = taskRepository;
        this.retryAttemptRepository = retryAttemptRepository;
        this.auditEventRepository = auditEventRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.failureInjector = failureInjector;
        this.interleaveHook = interleaveHook;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.postgresAdvisoryLocks = datasourceUrl != null && datasourceUrl.contains("postgresql");
    }

    public RetryOutcome retry(RetryCommand command) {
        validateKey(command.idempotencyKey());
        DataIntegrityViolationException lastUnique = null;
        for (int attempt = 0; attempt < UNIQUE_VIOLATION_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> execute(command));
            } catch (RuntimeException failure) {
                if (!isUniqueConstraintFailure(failure)) {
                    throw failure;
                }
                lastUnique = failure instanceof DataIntegrityViolationException integrity
                        ? integrity
                        : new DataIntegrityViolationException(failure.getMessage(), failure);
            }
        }
        throw lastUnique;
    }

    private RetryOutcome execute(RetryCommand command) {
        try {
            acquireIdempotencyLock(command);
            String fingerprint = fingerprint(command);

            var existingAttempt = retryAttemptRepository.lockByTenantIdAndIdempotencyKey(
                    command.tenantId(), command.idempotencyKey());
            if (existingAttempt.isPresent()) {
                return replayOrConflict(existingAttempt.get(), fingerprint);
            }
            interleaveHook.afterIdempotencyOwnerReserved();

            TaskEntity task = taskRepository
                    .lockByIdAndTenantId(command.taskId(), command.tenantId())
                    .orElseThrow(TaskNotFoundException::new);
            interleaveHook.afterTaskLocked();

            existingAttempt = retryAttemptRepository.findByTenantIdAndIdempotencyKey(
                    command.tenantId(), command.idempotencyKey());
            if (existingAttempt.isPresent()) {
                return replayOrConflict(existingAttempt.get(), fingerprint);
            }

            if (!task.getWorkflowId().equals(command.workflowId())) {
                throw new TaskNotFoundException();
            }
            if (task.getVersion() != command.expectedVersion()) {
                throw RetryConflictException.staleVersion(command.expectedVersion(), task.getVersion());
            }
            if (task.getStatus() != TaskStatus.FAILED_RETRYABLE) {
                throw RetryConflictException.notRetryable(task.getStatus());
            }

            Instant now = Instant.now();
            task.queueRetry(now);
            String attemptId = UUID.randomUUID().toString();
            var retryAttempt = new RetryAttemptEntity(
                    attemptId,
                    command.tenantId(),
                    task.getWorkflowId(),
                    task.getId(),
                    task.getTitle(),
                    TaskStatus.RETRY_QUEUED,
                    command.expectedVersion() + 1,
                    command.idempotencyKey(),
                    fingerprint,
                    now);
            retryAttemptRepository.save(retryAttempt);
            auditEventRepository.save(new AuditEventEntity(
                    UUID.randomUUID().toString(),
                    command.tenantId(),
                    task.getId(),
                    attemptId,
                    "TASK_RETRY_QUEUED",
                    now));
            outboxMessageRepository.save(new OutboxMessageEntity(
                    UUID.randomUUID().toString(),
                    command.tenantId(),
                    task.getId(),
                    attemptId,
                    "TASK_RETRY_REQUESTED",
                    "{\"taskId\":\"" + task.getId() + "\",\"attemptId\":\"" + attemptId + "\"}",
                    now));
            idempotencyRecordRepository.save(new IdempotencyRecordEntity(
                    command.tenantId(),
                    command.idempotencyKey(),
                    fingerprint,
                    attemptId,
                    now));
            entityManager.flush();
            failureInjector.afterOutboxInserted();

            return new RetryOutcome(
                    task.getId(),
                    task.getWorkflowId(),
                    task.getTitle(),
                    TaskStatus.RETRY_QUEUED,
                    command.expectedVersion() + 1,
                    attemptId,
                    false);
        } catch (OptimisticLockingFailureException ignored) {
            throw RetryConflictException.staleVersion(command.expectedVersion(), -1);
        }
    }

    private RetryOutcome replayOrConflict(RetryAttemptEntity attempt, String fingerprint) {
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

    private void acquireIdempotencyLock(RetryCommand command) {
        if (!postgresAdvisoryLocks) {
            return;
        }
        entityManager
                .createNativeQuery("select pg_advisory_xact_lock(hashtext(:lockKey))")
                .setParameter("lockKey", command.tenantId() + '\u0000' + command.idempotencyKey())
                .getSingleResult();
    }

    private static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidRetryRequestException("Idempotency-Key is missing or does not match the published format");
        }
    }

    static String fingerprint(RetryCommand command) {
        String canonical = command.tenantId()
                + '\u0000'
                + command.workflowId()
                + '\u0000'
                + command.taskId()
                + '\u0000'
                + command.expectedVersion();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required to compute the request fingerprint", error);
        }
    }

    private static boolean isUniqueConstraintFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AssignmentException) {
                return false;
            }
            if (current instanceof DataIntegrityViolationException
                    || current instanceof ConstraintViolationException
                    || current instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            String message = String.valueOf(current.getMessage()).toLowerCase();
            if (message.contains("pk_idempotency_records")
                    || message.contains("uq_retry_attempts_tenant_idempotency_key")
                    || (message.contains("unique") && message.contains("idempotency"))) {
                return true;
            }
        }
        return false;
    }
}
