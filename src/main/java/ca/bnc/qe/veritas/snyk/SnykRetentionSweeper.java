package ca.bnc.qe.veritas.snyk;

import java.time.Duration;
import java.time.Instant;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention sweep for the Snyk growth tables — snapshots + their vulns, seen alerts, and finished fix trains + steps.
 * Every poll (daily per watch, plus each manual refresh) inserts a snapshot and one vuln row per issue, so without
 * pruning these grow unbounded on a long-running server. The <b>latest snapshot per watch is always kept</b> as the
 * diff baseline; only older ones are removed. Human-wait fix trains (no {@code finishedAt}) are never pruned. A TTL
 * of {@code <= 0} disables that table's sweep.
 */
@Component
@Slf4j
public class SnykRetentionSweeper {

    private final SnykWatchRepository watches;
    private final SnykSnapshotRepository snapshots;
    private final SnykVulnRepository vulns;
    private final SnykAlertRepository alerts;
    private final SnykFixTrainRepository trains;
    private final SnykFixStepRepository steps;
    private final long snapshotDays;
    private final long alertDays;
    private final long fixTrainDays;

    public SnykRetentionSweeper(SnykWatchRepository watches, SnykSnapshotRepository snapshots, SnykVulnRepository vulns,
                                SnykAlertRepository alerts, SnykFixTrainRepository trains, SnykFixStepRepository steps,
                                @Value("${veritas.snyk.retention.snapshot-days:30}") long snapshotDays,
                                @Value("${veritas.snyk.retention.alert-days:60}") long alertDays,
                                @Value("${veritas.snyk.retention.fix-train-days:90}") long fixTrainDays) {
        this.watches = watches;
        this.snapshots = snapshots;
        this.vulns = vulns;
        this.alerts = alerts;
        this.trains = trains;
        this.steps = steps;
        this.snapshotDays = snapshotDays;
        this.alertDays = alertDays;
        this.fixTrainDays = fixTrainDays;
    }

    @Scheduled(initialDelayString = "${veritas.snyk.retention.sweep-ms:86400000}",
            fixedDelayString = "${veritas.snyk.retention.sweep-ms:86400000}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        int prunedSnapshots = snapshotDays > 0 ? pruneSnapshots(now.minus(Duration.ofDays(snapshotDays))) : 0;
        int prunedAlerts = alertDays > 0
                ? alerts.deleteBySeenTrueAndCreatedAtBefore(now.minus(Duration.ofDays(alertDays))) : 0;
        int prunedTrains = fixTrainDays > 0 ? pruneFixTrains(now.minus(Duration.ofDays(fixTrainDays))) : 0;
        if (prunedSnapshots + prunedAlerts + prunedTrains > 0) {
            log.info("Snyk retention swept {} snapshot(s), {} seen-alert(s), {} fix-train(s)",
                    prunedSnapshots, prunedAlerts, prunedTrains);
        }
    }

    /** Delete each watch's snapshots older than the cutoff (with their vulns), always keeping the latest baseline. */
    private int pruneSnapshots(Instant cutoff) {
        int pruned = 0;
        for (SnykWatch w : watches.findAll()) {
            String keep = snapshots.findFirstByWatchIdOrderByTakenAtDesc(w.getId())
                    .map(SnykSnapshot::getId).orElse(null);
            for (SnykSnapshot s : snapshots.findByWatchIdAndTakenAtBefore(w.getId(), cutoff)) {
                if (keep != null && keep.equals(s.getId())) {
                    continue;   // keep the most recent snapshot as the next poll's diff baseline
                }
                vulns.deleteBySnapshotId(s.getId());
                snapshots.delete(s);
                pruned++;
            }
        }
        return pruned;
    }

    /**
     * Prune only old <b>FAILED</b>, <b>CANCELLED</b> or <b>ALREADY_FIXED</b> trains (all noise — a failed run, one the
     * user abandoned, or a no-op where the framework already shipped the safe version). DONE trains are the cumulative
     * "fixes merged / PRs opened" record the executive dashboard reports, so deleting them would make those counters
     * silently shrink over time — keep them as the historical remediation trail.
     */
    private int pruneFixTrains(Instant cutoff) {
        int pruned = 0;
        for (SnykFixTrain t : trains.findByFinishedAtBefore(cutoff)) {
            if (!SnykFixStatus.FAILED.equals(t.getStatus()) && !SnykFixStatus.CANCELLED.equals(t.getStatus())
                    && !SnykFixStatus.ALREADY_FIXED.equals(t.getStatus())) {
                continue;
            }
            steps.deleteByTrainId(t.getId());
            trains.delete(t);
            pruned++;
        }
        return pruned;
    }
}
