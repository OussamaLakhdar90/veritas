package ca.bnc.qe.veritas.persistence;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic retention sweep for the append-only growth tables — {@code finding}, {@code cost_entry} and
 * {@code run_step}. Only {@code finding_index_snapshot} (session working state) had a sweep; these three grow
 * unbounded for every scan / LLM call / skill step, so on a long-running server they degrade the DB over time.
 * Each table has its own configurable TTL (a compliance retention window, not a cache); a TTL of {@code <= 0}
 * disables the sweep for that table. The scan's HTML report on disk remains the historical record of truth, so a
 * very old scan whose finding rows are swept is still auditable.
 */
@Component
@Slf4j
public class RetentionSweeper {

    private final FindingRecordRepository findings;
    private final CostEntryRepository costs;
    private final RunStepRepository runSteps;
    private final long findingDays;
    private final long costDays;
    private final long runStepDays;

    public RetentionSweeper(FindingRecordRepository findings, CostEntryRepository costs, RunStepRepository runSteps,
                            @Value("${veritas.retention.findings-days:180}") long findingDays,
                            @Value("${veritas.retention.cost-days:365}") long costDays,
                            @Value("${veritas.retention.run-step-days:90}") long runStepDays) {
        this.findings = findings;
        this.costs = costs;
        this.runSteps = runSteps;
        this.findingDays = findingDays;
        this.costDays = costDays;
        this.runStepDays = runStepDays;
    }

    @Scheduled(initialDelayString = "${veritas.retention.sweep-ms:86400000}",
            fixedDelayString = "${veritas.retention.sweep-ms:86400000}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        int f = findingDays > 0 ? findings.deleteByCreatedAtBefore(now.minus(Duration.ofDays(findingDays))) : 0;
        int c = costDays > 0 ? costs.deleteByCreatedAtBefore(now.minus(Duration.ofDays(costDays))) : 0;
        int r = runStepDays > 0 ? runSteps.deleteByCreatedAtBefore(now.minus(Duration.ofDays(runStepDays))) : 0;
        if (f + c + r > 0) {
            log.info("Retention sweep deleted {} finding (>{}d), {} cost (>{}d), {} run-step (>{}d) row(s)",
                    f, findingDays, c, costDays, r, runStepDays);
        }
    }
}
