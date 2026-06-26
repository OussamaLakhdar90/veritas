package ca.bnc.qe.veritas.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
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

    @Test
    void callWriteDoesNotReplayNonConnectFailures() {
        // A 5xx / read-timeout on a non-idempotent write may already have been processed → must NOT be re-sent.
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> retries.callWrite(() -> {
            attempts.incrementAndGet();
            throw new ResourceAccessException("read timed out");   // no ConnectException cause
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(attempts.get()).isEqualTo(1);   // sent exactly once — no duplicate write
    }

    @Test
    void callWriteReplaysConnectFailuresWhichNeverReachedTheServer() {
        // A connection failure means the request was never sent, so replaying it cannot duplicate the write.
        AtomicInteger attempts = new AtomicInteger();
        String result = retries.callWrite(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ResourceAccessException("connect failed", new ConnectException("refused"));
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void isConnectFailureWalksTheCauseChain() {
        assertThat(Retries.isConnectFailure(
                new ResourceAccessException("x", new ConnectException("refused")))).isTrue();
        assertThat(Retries.isConnectFailure(new ResourceAccessException("read timed out"))).isFalse();
        assertThat(Retries.isConnectFailure(new RestClientException("503"))).isFalse();
    }
}
