package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.RetryNotImplementedException;
import org.springframework.stereotype.Service;

@Service
public class RetryService {

    /**
     * TODO(candidate): implement the safe retry transaction described in the assignment.
     *
     * <p>The starter deliberately returns a stable 501 so the rest of the application can run
     * before this method is complete. Do not replace this with partial writes.
     */
    public RetryOutcome retry(RetryCommand command) {
        throw new RetryNotImplementedException();
    }
}
