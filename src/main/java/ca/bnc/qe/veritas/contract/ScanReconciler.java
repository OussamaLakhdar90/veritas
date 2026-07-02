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
 * can't cover. On startup, any RUNNING scan is from a prior process and is marked FAILED. Periodically, a scan
 * whose row has seen NO progress write for the configured ceiling is considered hung: its worker is interrupted
 * (freeing the pool slot for queued scans) and the row is marked FAILED. The timeout is based on the last
 * heartbeat ({@code updatedAt}, refreshed by every stage/detail persist), never on submit time — so a scan
 * waiting in the queue or a long scan still actively streaming LLM output is never falsely failed.
 */
@Component
@Slf4j
public class ScanReconciler {

    private final ScanRepository scans;
    private final AsyncScanRunner runner;

    @Value("${veritas.scan.max-runtime-minutes:60}")
    private long maxRuntimeMinutes;

    public ScanReconciler(ScanRepository scans, AsyncScanRunner runner) {
        this.scans = scans;
        this.runner = runner;
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
            if (ScanStages.QUEUED.equals(s.getStage())) {
                // Hasn't started — it's waiting for a pool slot, not hung. It runs as soon as a slot frees
                // (hung slot-holders are interrupted below); orphans from a crash are recovered at startup.
                continue;
            }
            Instant heartbeat = lastProgress(s);
            if (heartbeat != null && heartbeat.isBefore(cutoff)) {
                boolean interrupted = runner.cancel(s.getId());
                log.warn("Scan {} [{}] made no progress for over {} min — {}", s.getId(), s.getServiceName(),
                        maxRuntimeMinutes, interrupted ? "worker interrupted" : "no live worker found");
                fail(s, "Scan made no progress for over " + maxRuntimeMinutes + " min");
            }
        }
    }

    /** The row's last write ({@code updatedAt} refreshes on every stage/detail persist — the true liveness
     *  signal); falls back to {@code startedAt} for rows read before Hibernate populated the timestamp. */
    private static Instant lastProgress(Scan s) {
        return s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getStartedAt();
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
