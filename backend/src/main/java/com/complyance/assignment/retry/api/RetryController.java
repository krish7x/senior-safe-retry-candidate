package com.complyance.assignment.retry.api;

import com.complyance.assignment.retry.application.RetryCommand;
import com.complyance.assignment.retry.application.RetryOutcome;
import com.complyance.assignment.retry.application.RetryService;
import com.complyance.assignment.security.TenantPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows/{workflowId}/tasks/{taskId}/retry")
public class RetryController {

    private final RetryService retryService;

    public RetryController(RetryService retryService) {
        this.retryService = retryService;
    }

    @PostMapping
    ResponseEntity<RetryOutcome> retry(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String workflowId,
            @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RetryRequest request) {
        var outcome = retryService.retry(new RetryCommand(
                principal.tenantId(),
                workflowId,
                taskId,
                idempotencyKey,
                request.expectedVersion()));
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(outcome);
    }
}
