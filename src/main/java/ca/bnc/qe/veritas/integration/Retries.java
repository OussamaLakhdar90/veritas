package ca.bnc.qe.veritas.integration;

import java.util.function.Supplier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/** Wraps a transient HTTP call with bounded, backed-off retries (rate-limit / 5xx resilience). */
@Component
public class Retries {

    private final RetryTemplate retryTemplate;

    public Retries(RetryTemplate retryTemplate) {
        this.retryTemplate = retryTemplate;
    }

    public <T> T call(Supplier<T> operation) {
        return retryTemplate.execute(ctx -> operation.get());
    }
}
