package ca.bnc.qe.veritas.integration;

import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Bounded exponential-backoff retry for TRANSIENT integration failures only: network/IO, 5xx, and 429
 * (rate-limit). Deliberately does NOT retry other 4xx (a bad request won't get better by retrying) — which
 * also avoids re-sending non-idempotent writes (createIssue/createTest) on a client error.
 */
@Configuration
public class RetryConfig {

    @Bean
    public RetryTemplate retryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(500, 2.0, 5000)
                .retryOn(ResourceAccessException.class)                 // connect/read IO
                .retryOn(HttpServerErrorException.class)                // 5xx
                .retryOn(HttpClientErrorException.TooManyRequests.class) // 429 rate-limit
                .retryOn(IOException.class)
                .build();
    }
}
