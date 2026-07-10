package ca.bnc.qe.veritas.evolve;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily background poll that recomputes Engine-Evolution proposals from the accumulated field votes, so new
 * classifications surface without a human clicking "Refresh". OFF by default ({@code veritas.evolve.poll-enabled}) —
 * a refresh calls the AI advisor, which incurs LLM cost. A thin scheduler delegating to
 * {@link EngineEvolutionService} (mirrors {@code SnykPoller}); a poll failure is logged, never propagated.
 */
@Component
@ConditionalOnProperty(name = "veritas.evolve.poll-enabled", havingValue = "true")
@Slf4j
public class ClassificationPoller {

    private final EngineEvolutionService service;

    public ClassificationPoller(EngineEvolutionService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${veritas.evolve.poll-interval-ms:86400000}",
            initialDelayString = "${veritas.evolve.poll-initial-delay-ms:120000}")
    public void poll() {
        try {
            service.refresh("scheduler");
        } catch (Exception e) {
            log.warn("Scheduled Engine-Evolution proposal refresh failed: {}", e.getMessage());
        }
    }
}
