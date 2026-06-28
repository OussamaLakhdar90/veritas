package ca.bnc.qe.veritas.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.execution.ExecutionSummary.TestOutcome;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads the latest test execution status back from Xray (read-only — Xray is where the generated tests actually run)
 * and turns it into a completion summary: passed / failed / blocked / not-run, plus the deviations a completion report
 * must explain. This is the ISTQB "test completion" phase the audit found missing — it makes coverage mean
 * <em>verified</em>, not just designed.
 *
 * <p>Every step is logged (the JQL, the raw statuses, the normalised buckets, the verdict) so the read path — which
 * can only be fully validated against a live Xray — is easy to debug during the first live run.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final XrayClient xray;

    public ExecutionService(XrayClient xray) {
        this.xray = xray;
    }

    public ExecutionSummary completion(String serviceName, String jql, String owner) {
        log.info("ExecutionService.completion START service='{}' jql='{}' owner='{}'", serviceName, jql, owner);
        List<XrayRunStatus> statuses = xray.getTestRunStatuses(jql);
        log.info("ExecutionService read {} run-status row(s) from Xray for jql='{}'", statuses.size(), jql);

        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int notRun = 0;
        List<TestOutcome> deviations = new ArrayList<>();
        for (XrayRunStatus s : statuses) {
            String outcome = normalise(s.status());
            log.debug("ExecutionService outcome: {} raw='{}' -> {}", s.testKey(), s.status(), outcome);
            switch (outcome) {
                case "PASSED" -> passed++;
                case "FAILED" -> {
                    failed++;
                    deviations.add(new TestOutcome(s.testKey(), s.status(), outcome));
                }
                case "BLOCKED" -> {
                    blocked++;
                    deviations.add(new TestOutcome(s.testKey(), s.status(), outcome));
                }
                default -> notRun++;
            }
        }

        int total = statuses.size();
        String verdict = verdict(total, passed, failed, blocked, notRun);
        log.info("ExecutionService.completion DONE service='{}' total={} passed={} failed={} blocked={} notRun={} -> {}",
                serviceName, total, passed, failed, blocked, notRun, verdict);
        return new ExecutionSummary(serviceName, jql, total, passed, failed, blocked, notRun, deviations, verdict);
    }

    /** Map a raw Xray/Jira status string to a stable outcome bucket. */
    static String normalise(String status) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (s.contains("PASS")) {
            return "PASSED";
        }
        if (s.contains("FAIL")) {
            return "FAILED";
        }
        if (s.contains("BLOCK") || s.contains("ABORT")) {
            return "BLOCKED";
        }
        return "NOT_RUN";   // TODO / executing / done-without-a-result / unknown — not a verified outcome
    }

    private static String verdict(int total, int passed, int failed, int blocked, int notRun) {
        if (total == 0) {
            return "No executed tests found for this selection — run the tests in Xray, then re-check.";
        }
        String counts = total + " designed · " + passed + " passed · " + failed + " failed · "
                + blocked + " blocked · " + notRun + " not run";
        if (failed > 0 || blocked > 0) {
            return counts + " — NEEDS ATTENTION (failures/blocks must be explained or fixed before release).";
        }
        if (notRun > 0) {
            return counts + " — INCOMPLETE (not every test has been executed).";
        }
        return counts + " — ALL PASSED.";
    }
}
