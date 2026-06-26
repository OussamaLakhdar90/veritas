package ca.bnc.qe.veritas.llm.copilot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the live Copilot billing multipliers fresh in long-running (server) mode. The CLI {@code copilot-login}
 * command refreshes once at sign-in; without this, a server started months ago would bill at stale rates.
 *
 * <p>Best-effort: a refresh failure (not signed in, network blip) is logged and ignored — cost falls back to
 * the static catalog. Skipped entirely in {@code mock} mode (no real billing to track).
 */
@Component
@Slf4j
public class CopilotModelsRefresher {

    private final CopilotModelsClient models;

    @Value("${veritas.llm.mode:mock}")
    private String mode;

    public CopilotModelsRefresher(CopilotModelsClient models) {
        this.models = models;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refreshQuietly("startup");
    }

    /** Periodic refresh so pricing changes published by GitHub are picked up without a restart. */
    @Scheduled(fixedDelayString = "${veritas.copilot.models-refresh-ms:21600000}", initialDelay = 21_600_000)
    public void scheduled() {
        refreshQuietly("scheduled");
    }

    private void refreshQuietly(String when) {
        if (mode == null || mode.equalsIgnoreCase("mock")) {
            return;   // no live billing to track locally
        }
        try {
            int n = models.refresh();
            if (n == 0) {
                // 0 multipliers → billing silently uses fallback rates; visible because the live BNC run hit exactly
                // this ("Refreshed 0 Copilot model multiplier(s)") when the requested model wasn't one Copilot serves.
                log.warn("Copilot multiplier {} refresh returned 0 model(s) — billing uses fallback rates; confirm "
                        + "the configured model is one Copilot actually serves.", when);
            } else {
                log.info("Copilot multiplier {} refresh: {} model(s)", when, n);
            }
        } catch (Exception e) {
            // A failed refresh means cost is billed at stale/fallback rates — worth a WARN, not a hidden DEBUG line.
            log.warn("Copilot multiplier {} refresh failed: {} — billing uses fallback rates.", when, e.getMessage());
        }
    }
}
