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
        // Rows created before the @Version column existed have NULL version; with a wrapper Long version a NULL
        // makes Spring Data treat the row as new (save() → INSERT → PK clash). Backfill to 0 before we touch them.
        int backfilled = scans.backfillNullVersions();
        if (backfilled > 0) {
            log.info("Backfilled @Version on {} pre-existing scan row(s)", backfilled);
        }
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
        s.setFailedStage(s.getStage());   // keep where it was when interrupted / timed out (consistent with the worker paths)
        s.setStatus(RunStatus.FAILED);
        s.setStage(ScanStages.FAILED);
        s.setStageDetail(null);
        s.setErrorMessage(reason);
        s.setFinishedAt(Instant.now());
        try {
            scans.save(s);
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // a live worker updated this scan between our read and write — it isn't actually stuck; skip it (it
            // self-heals next tick). Per-call catch so the rest of the batch is still processed.
            log.warn("Scan {} was updated concurrently — skipping timeout (it isn't stuck)", s.getId());
        }
    }
}
