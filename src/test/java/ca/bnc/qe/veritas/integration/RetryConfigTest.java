package ca.bnc.qe.veritas.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/** The RetryConfig template retries transient failures and (when a registry is present) counts each retried attempt. */
class RetryConfigTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(registry);
        return p;
    }

    @Test
    void retriesTransientFailuresAndCountsEachAttemptViaTheListener() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetryTemplate template = new RetryConfig().retryTemplate(provider(registry));
        AtomicInteger attempts = new AtomicInteger();

        String result = template.execute(ctx -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ResourceAccessException("transient io");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
        // Two failures before the third attempt succeeded → two retry-counter increments.
        assertThat(registry.counter("veritas.integration.retries").count()).isEqualTo(2.0);
    }

    @Test
    void worksWithoutAMeterRegistry() {
        RetryTemplate template = new RetryConfig().retryTemplate(provider(null));
        AtomicInteger attempts = new AtomicInteger();
        String result = template.execute(ctx -> {
            if (attempts.incrementAndGet() < 2) {
                throw new ResourceAccessException("io");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");   // listener guards on null registry → no NPE
    }
}
