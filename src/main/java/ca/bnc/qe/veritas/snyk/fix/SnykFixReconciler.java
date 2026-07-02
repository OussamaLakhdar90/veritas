package ca.bnc.qe.veritas.snyk.fix;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps fix trains honest across restarts and hangs. On startup, any train left mid-flight (a process died during a
 * cascade) is marked FAILED; on a schedule, a train stuck in an active stage past the max runtime is failed too.
 * Trains waiting on a human (AWAITING_MANUAL_FIX / PR_OPEN) are never timed out — they may legitimately wait days.
 */
@Component
@Slf4j
public class SnykFixReconciler {

    private final SnykFixTrainRepository trains;

    @Value("${veritas.snyk.fix.max-runtime-minutes:30}")
    private long maxRuntimeMinutes;

    public SnykFixReconciler(SnykFixTrainRepository trains) {
        this.trains = trains;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        List<SnykFixTrain> stuck = trains.findByStatusIn(SnykFixStatus.MACHINE_DRIVEN);
        stuck.forEach(t -> fail(t, "The fix was interrupted by a process restart."));
        if (!stuck.isEmpty()) {
            log.warn("Recovered {} Snyk fix train(s) left mid-flight → FAILED", stuck.size());
        }
    }

    @Scheduled(fixedDelayString = "${veritas.snyk.fix.reconcile-ms:300000}",
            initialDelayString = "${veritas.snyk.fix.reconcile-ms:300000}")
    public void timeoutStale() {
        Instant cutoff = Instant.now().minus(maxRuntimeMinutes, ChronoUnit.MINUTES);
        for (SnykFixTrain t : trains.findByStatusIn(SnykFixStatus.MACHINE_DRIVEN)) {
            if (t.getStartedAt() != null && t.getStartedAt().isBefore(cutoff)) {
                fail(t, "The fix exceeded the maximum runtime of " + maxRuntimeMinutes + " min.");
            }
        }
    }

    private void fail(SnykFixTrain t, String reason) {
        try {
            t.setFailedStage(t.getStatus());
            t.setStatus(SnykFixStatus.FAILED);
            t.setErrorMessage(reason);
            t.setFinishedAt(Instant.now());
            trains.save(t);
        } catch (RuntimeException e) {
            // A concurrent worker update won the optimistic-lock race — its state wins; skip this one.
            log.debug("Reconcile skipped train {} (concurrent update): {}", t.getId(), e.getMessage());
        }
    }
}
