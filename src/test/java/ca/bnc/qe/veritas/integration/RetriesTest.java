package ca.bnc.qe.veritas.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClientException;

class RetriesTest {

    private final Retries retries = new Retries(
            RetryTemplate.builder().maxAttempts(3).fixedBackoff(1).retryOn(RestClientException.class).build());

    @Test
    void retriesTransientFailuresThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = retries.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RestClientException("transient 503");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }
}
