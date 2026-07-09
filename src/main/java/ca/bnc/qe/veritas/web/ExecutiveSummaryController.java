package ca.bnc.qe.veritas.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.contract.FindingMapper;
import ca.bnc.qe.veritas.contract.ReleaseVerdict;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The executive rollup behind the dashboard's value story: breaking changes caught (deduped across re-scans,
 * human-dismissed excluded), per-service release-safe verdicts (computed by the SAME {@link ReleaseVerdict}
 * the HTML report renders — the two can never disagree), and disposition stats scoped to each service's
 * latest completed scan (the human-in-the-loop governance proof). Query-time aggregation over existing
 * tables — nothing new is persisted, so history and seeded data roll up identically.
 */
@RestController
@RequestMapping("/api/v1/summary")
public class ExecutiveSummaryController {

    /** Statuses a human used to dismiss a finding — excluded from "caught"/"open" counts. */
    private static final Set<String> DISMISSED = Set.of("REJECTED", "FALSE_POSITIVE", "WONT_FIX");

    private static final List<String> BREAKING_TYPES = EnumSet.allOf(FindingType.class).stream()
            .filter(DiffEngine::isBreaking).map(Enum::name).toList();

    private final ScanRepository scans;
    private final FindingRecordRepository findings;
    private final ca.bnc.qe.veritas.config.GateProperties gate;

    public ExecutiveSummaryController(ScanRepository scans, FindingRecordRepository findings,
                                      ca.bnc.qe.veritas.config.GateProperties gate) {
        this.scans = scans;
        this.findings = findings;
        this.gate = gate;
    }

    @GetMapping("/executive")
    public ExecutiveSummary executive() {
        List<ServiceSummary> perService = new ArrayList<>();
        Tally t = new Tally();
        // The portfolio "blocking" total is the SUM of the per-service release verdicts — the same gate the
        // scorecard shows — so a low-confidence/AI-disputed CRITICAL (excluded by ReleaseVerdict) can't inflate
        // the DecisionQueue count above what actually blocks a release.
        long blockingOpen = 0;
        for (Scan scan : latestCompletedPerService()) {
            List<FindingRecord> rows = findings.findByScanIdOrderBySeverityAsc(scan.getId());
            // Exclude human-dismissed findings BEFORE the verdict so the badge (releaseSafe), the blocking count and
            // the breaking count all use the same inclusion rule — a fully-triaged service can't read FAIL with a
            // breaking count of 0, and a dismissed blocker can't inflate the DecisionQueue. (Tally below still sees
            // every row: the disposition stats deliberately count the dismissals.)
            List<Finding> live = rows.stream()
                    .filter(r -> !isDismissed(r.getStatus())).map(FindingMapper::toFinding).toList();
            ReleaseVerdict verdict = ReleaseVerdict.of(live, gate);
            perService.add(new ServiceSummary(scan.getServiceName(),
                    verdict.breaking(), verdict.blocking(), verdict.releaseSafe(), scan.getId()));
            blockingOpen += verdict.blocking();
            rows.forEach(t::add);
        }
        perService.sort(Comparator.comparing(ServiceSummary::service));
        long caught = findings.countDistinctCaughtByTypes(BREAKING_TYPES);
        return new ExecutiveSummary(
                new Totals(caught, blockingOpen, t.aiDisputed),
                perService,
                new Dispositions(t.reviewed, t.accepted, t.rejected, t.jiraCreated, t.open, t.aiDisputed));
    }

    /** The newest COMPLETED scan per service — RUNNING/FAILED rows are excluded (they carry no release verdict). */
    private List<Scan> latestCompletedPerService() {
        Map<String, Scan> latest = scans.findAll().stream()
                .filter(s -> s.getStatus() == RunStatus.COMPLETED && s.getStartedAt() != null)
                .collect(Collectors.toMap(Scan::getServiceName, s -> s,
                        (a, b) -> a.getStartedAt().isAfter(b.getStartedAt()) ? a : b));
        return List.copyOf(latest.values());
    }

    private static boolean isDismissed(String status) {
        return status != null && DISMISSED.contains(status);
    }

    /** Disposition tallies over the latest scans (mutable accumulator kept local to one request). */
    private static final class Tally {
        long reviewed;
        long accepted;
        long rejected;
        long jiraCreated;
        long open;
        long aiDisputed;

        void add(FindingRecord r) {
            String status = r.getStatus() == null ? "OPEN" : r.getStatus();
            if (r.getReviewedAt() != null) {
                reviewed++;
            }
            switch (status) {
                case "ACCEPTED" -> accepted++;
                case "REJECTED", "FALSE_POSITIVE" -> rejected++;
                case "JIRA_CREATED" -> jiraCreated++;
                case "OPEN" -> open++;
                default -> { /* TRIAGED/FIXED/WONT_FIX counted in reviewed only */ }
            }
            if (r.isAiDisputed()) {
                aiDisputed++;
            }
        }
    }

    public record ServiceSummary(String service, long breakingCount,
                                 long blockingCount, String releaseSafe, String latestScanId) {}

    public record Totals(long breakingFindingsCaught, long blockingOpen, long disputedByAi) {}

    public record Dispositions(long reviewed, long accepted, long rejected, long jiraCreated, long open,
                               long aiDisputed) {}

    public record ExecutiveSummary(Totals totals, List<ServiceSummary> perService, Dispositions dispositions) {}
}
