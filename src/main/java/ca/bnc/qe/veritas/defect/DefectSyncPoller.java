package ca.bnc.qe.veritas.defect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background poller that refreshes Jira defect statuses on a fixed interval. Off by default — the bean is
 * only created when {@code veritas.defect.poll-enabled=true}, so tests and dev runs never call Jira in the
 * background. Manual refresh is always available via {@code POST /api/v1/defects/sync}.
 */
@Component
@ConditionalOnProperty(name = "veritas.defect.poll-enabled", havingValue = "true")
@Slf4j
public class DefectSyncPoller {

    private final DefectSyncService syncService;

    public DefectSyncPoller(DefectSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(
            fixedDelayString = "${veritas.defect.poll-interval-ms:300000}",
            initialDelayString = "${veritas.defect.poll-interval-ms:300000}")
    public void poll() {
        try {
            syncService.syncAll();
        } catch (Exception e) {
            log.warn("Scheduled defect sync failed: {}", e.getMessage());
        }
    }
}
