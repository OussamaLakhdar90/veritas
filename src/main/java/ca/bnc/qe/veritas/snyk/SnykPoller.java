package ca.bnc.qe.veritas.snyk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background poller that refreshes watched-repo Snyk vulnerabilities on a fixed interval. Off by default — the
 * bean is only created when {@code veritas.snyk.poll-enabled=true}, so tests and dev runs never call Snyk in the
 * background. Manual refresh is always available via {@code POST /api/v1/snyk/refresh}.
 */
@Component
@ConditionalOnProperty(name = "veritas.snyk.poll-enabled", havingValue = "true")
@Slf4j
public class SnykPoller {

    private final SnykPollService pollService;

    public SnykPoller(SnykPollService pollService) {
        this.pollService = pollService;
    }

    @Scheduled(
            fixedDelayString = "${veritas.snyk.poll-interval-ms:300000}",
            initialDelayString = "${veritas.snyk.poll-interval-ms:300000}")
    public void poll() {
        try {
            pollService.pollAll();
        } catch (Exception e) {
            log.warn("Scheduled Snyk poll failed: {}", e.getMessage());
        }
    }
}
