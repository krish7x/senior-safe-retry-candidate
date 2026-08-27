package com.complyance.assignment.retry.api;

import com.complyance.assignment.retry.application.RetryPauseService;
import com.complyance.assignment.security.TenantPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emergency operations control: pause all new retries for the authenticated tenant.
 *
 * <p>The tenant is derived from the bearer token only; nothing in the request body or path
 * is trusted for identity. A valid request returns {@code 204}, and calling it again also
 * returns {@code 204} because pausing is idempotent.
 */
@RestController
@RequestMapping("/api/retries/pause")
public class RetryPauseController {

    private final RetryPauseService retryPauseService;

    public RetryPauseController(RetryPauseService retryPauseService) {
        this.retryPauseService = retryPauseService;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pause(@AuthenticationPrincipal TenantPrincipal principal) {
        retryPauseService.pause(principal.tenantId());
    }
}
