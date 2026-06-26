package ca.bnc.qe.veritas.integration;

import java.io.IOException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Bounded exponential-backoff retry for TRANSIENT integration failures only: network/IO, 5xx, and 429
 * (rate-limit). Deliberately does NOT retry other 4xx (a bad request won't get better by retrying) — which
 * also avoids re-sending non-idempotent writes (createIssue/createTest) on a client error. A {@link RetryListener}
 * makes retries observable: each retried attempt is logged (WARN) and counted ({@code veritas.integration.retries}),
 * so the previously-invisible retry layer shows up in logs + /actuator/metrics.
 */
@Configuration
@Slf4j
public class RetryConfig {

    @Bean
    public RetryTemplate retryTemplate(ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(500, 2.0, 5000)
                .retryOn(ResourceAccessException.class)                 // connect/read IO
                .retryOn(HttpServerErrorException.class)                // 5xx
                .retryOn(HttpClientErrorException.TooManyRequests.class) // 429 rate-limit
                .retryOn(IOException.class)
                .withListener(new RetryListener() {
                    @Override
                    public <T, E extends Throwable> void onError(RetryContext context,
                            RetryCallback<T, E> callback, Throwable throwable) {
                        // Fires once per failed attempt; getRetryCount() is the number of failures so far.
                        log.warn("Transient integration call failed (attempt {} of 3), will retry if attempts remain: {}",
                                context.getRetryCount(), throwable.getMessage());
                        if (registry != null) {
                            registry.counter("veritas.integration.retries").increment();
                        }
                    }
                })
                .build();
    }
}
