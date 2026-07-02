package ca.bnc.qe.veritas.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.GateDecisionRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.snyk.SnykWatchRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the app with the demo seed ON against a dedicated SQLite file and verifies the portfolio is
 * coherent: fidelity chains, dispositions, defect links referencing real findings, a pending gate, the
 * Snyk graph in reconciler-safe states, and back-dated history (the @CreationTimestamp JDBC shim works).
 * Idempotency is asserted by re-running the seeder against the already-seeded database.
 */
@SpringBootTest(properties = {
        "veritas.demo.seed=true",
        "spring.datasource.url=jdbc:sqlite:target/demo-seed-test.db?journal_mode=WAL&busy_timeout=5000",
})
class DemoPortfolioSeederTest {

    @Autowired DemoPortfolioSeeder seeder;
    @Autowired ScanRepository scans;
    @Autowired FindingRecordRepository findings;
    @Autowired DefectLinkRepository defects;
    @Autowired CostEntryRepository costs;
    @Autowired GateDecisionRepository gates;
    @Autowired SnykWatchRepository watches;
    @Autowired SnykFixTrainRepository trains;
    @Autowired SnykFixStepRepository steps;
    @Autowired TestStrategyRepository strategies;

    @Test
    void seedsACoherentPortfolioAndIsIdempotent() throws Exception {
        long scanCount = scans.count();
        assertThat(scanCount).isEqualTo(32);   // 8 services × 4 scans

        // Idempotent: a second run against seeded data must change nothing.
        seeder.run(null);
        assertThat(scans.count()).isEqualTo(scanCount);

        // Fidelity chain: every non-first scan carries the previous score; latest scans all scored.
        List<Scan> ciam = scans.findAll().stream()
                .filter(s -> "ciam-policies".equals(s.getServiceName()))
                .sorted(Comparator.comparing(Scan::getStartedAt)).toList();
        assertThat(ciam).hasSize(4);
        assertThat(ciam.get(0).getPreviousFidelityScore()).isNull();
        for (int i = 1; i < ciam.size(); i++) {
            assertThat(ciam.get(i).getPreviousFidelityScore()).isEqualTo(ciam.get(i - 1).getFidelityScore());
        }
        assertThat(ciam.get(3).getFidelityScore()).isEqualTo(84);

        // Dispositions: accepted findings carry the audit trail; the AI disputed at least one of its own.
        List<FindingRecord> all = findings.findAll();
        assertThat(all).isNotEmpty();
        assertThat(all.stream().filter(f -> "ACCEPTED".equals(f.getStatus())))
                .isNotEmpty().allSatisfy(f -> {
                    assertThat(f.getReviewedBy()).isNotBlank();
                    assertThat(f.getReviewedAt()).isNotNull();
                });
        assertThat(all.stream().anyMatch(FindingRecord::isAiDisputed)).isTrue();

        // Defect links point at REAL seeded findings (findingId is NOT NULL + unique) with a done/open mix.
        List<DefectLink> links = defects.findAll();
        assertThat(links).isNotEmpty().allSatisfy(d ->
                assertThat(findings.findById(d.getFindingId())).isPresent());
        assertThat(links.stream().map(DefectLink::getJiraStatusCategory).distinct().count()).isGreaterThan(1);

        // Exactly one decision waits for a human (the DecisionQueue demo beat).
        assertThat(gates.findAll().stream().filter(g -> "PENDING".equals(g.getStatus()))).hasSize(1);

        // Snyk graph: 3 watches; trains only in reconciler-safe states; every DONE step carries a PR.
        assertThat(watches.count()).isEqualTo(3);
        assertThat(trains.findAll()).extracting(t -> t.getStatus())
                .containsExactlyInAnyOrder("DONE", "DONE", "AWAITING_CONFIRM");
        assertThat(steps.findAll().stream().filter(s -> s.getPrUrl() != null)).hasSize(8);

        // Test assets: every service has a strategy (pipeline table shows no dash-wall).
        assertThat(strategies.count()).isEqualTo(8);

        // Back-dating: the JDBC shim really moved created_at into the past AND Hibernate reads it back.
        Instant fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS);
        assertThat(costs.findAll().stream().map(CostEntry::getCreatedAt)
                .anyMatch(c -> c != null && c.isBefore(fiveDaysAgo)))
                .as("cost entries must be spread over 30 days, not piled on today")
                .isTrue();
    }
}
