package ca.bnc.qe.veritas.contract;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers scans left in RUNNING by a hard crash (JVM kill, power loss) — the one failure window a transaction
 * can't cover. On startup, any RUNNING scan is from a prior process and is marked FAILED; periodically, a scan
 * RUNNING longer than the configured ceiling is marked FAILED so the dashboard never shows a perpetual spinner.
 */
@Component
@Slf4j
public class ScanReconciler {

    private final ScanRepository scans;

    @Value("${veritas.scan.max-runtime-minutes:60}")
    private long maxRuntimeMinutes;

    public ScanReconciler(ScanRepository scans) {
        this.scans = scans;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedScans() {
        List<Scan> stuck = scans.findByStatus(RunStatus.RUNNING);
        for (Scan s : stuck) {
            fail(s, "Scan was interrupted by a process restart");
        }
        if (!stuck.isEmpty()) {
            log.warn("Recovered {} scan(s) left RUNNING by a previous run → FAILED", stuck.size());
        }
    }

    @Scheduled(fixedDelayString = "${veritas.scan.reconcile-ms:300000}", initialDelay = 300_000)
    public void timeOutStaleScans() {
        Instant cutoff = Instant.now().minus(maxRuntimeMinutes, ChronoUnit.MINUTES);
        for (Scan s : scans.findByStatus(RunStatus.RUNNING)) {
            if (s.getStartedAt() != null && s.getStartedAt().isBefore(cutoff)) {
                fail(s, "Scan exceeded the maximum runtime of " + maxRuntimeMinutes + " min");
            }
        }
    }

    private void fail(Scan s, String reason) {
        s.setStatus(RunStatus.FAILED);
        s.setErrorMessage(reason);
        s.setFinishedAt(Instant.now());
        scans.save(s);
    }
}
