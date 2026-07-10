package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository.DisputeRow;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-DB proof of the disputed-findings triage query: the finding→scan theta-join surfaces the service + reason +
 * verdict, excludes human-dismissed rows, and — unlike the classification-vote query — KEEPS service-less scans so
 * the per-type totals reconcile with {@code countDistinctDisputed()}. Also proves a dispute verdict is found across
 * scans even while the finding's lifecycle status is still OPEN (the disposition lookup would miss it).
 * {@code @Transactional} rolls the inserts back so this doesn't pollute the shared test DB.
 */
@SpringBootTest
@Transactional
class DisputeClusterQueryTest {

    @Autowired private FindingRecordRepository findings;
    @Autowired private ScanRepository scans;
    @Autowired private DisputeClusterService clusters;

    @Test
    void collectsDisputedRowsJoinedToServiceIncludingServiceLessExcludingDismissed() {
        long baseDisputed = findings.countDistinctDisputed();

        Scan a = scan("svc-a");
        Scan noService = scan(null);
        disputed(a.getId(), "d1", "int32 vs integer", "NEEDS_DETECTION_FIX");
        disputed(a.getId(), "d2", "ref vs inline", null);
        disputed(noService.getId(), "d3", "no-service dispute", null);   // kept — no service filter (reconciles w/ KPI)
        dismissed(a.getId(), "d4");                                      // FALSE_POSITIVE → excluded

        List<DisputeRow> rows = findings.findDisputedRows().stream()
                .filter(r -> Set.of("d1", "d2", "d3", "d4").contains(r.getFingerprint()))
                .toList();

        assertThat(rows).extracting(DisputeRow::getFingerprint)
                .containsExactlyInAnyOrder("d1", "d2", "d3");   // d4 dismissed → gone; d3 (null service) kept
        DisputeRow d1 = rows.stream().filter(r -> "d1".equals(r.getFingerprint())).findFirst().orElseThrow();
        assertThat(d1.getService()).isEqualTo("svc-a");
        assertThat(d1.getReason()).isEqualTo("int32 vs integer");
        assertThat(d1.getVerdict()).isEqualTo("NEEDS_DETECTION_FIX");

        // Reconciles with the KPI: d1 + d2 + d3 disputed (d4 dismissed) → +3 distinct fingerprints.
        assertThat(findings.countDistinctDisputed()).isEqualTo(baseDisputed + 3);
    }

    @Test
    void clusterCountsReconcileWithTheDisputedKpiEvenWhenAFingerprintRecursAcrossScans() {
        long baseKpi = findings.countDistinctDisputed();
        long baseClusterSum = clusters.computeClusters().stream().mapToInt(DisputeCluster::count).sum();

        Scan s1 = scan("svc-a");
        Scan s2 = scan("svc-a");
        disputed(s1.getId(), "recurring", "reason", null);   // SAME fingerprint on two scans (the carry-forward path)…
        disputed(s2.getId(), "recurring", "reason", null);   // …must count ONCE — this is exactly what DISTINCT is for
        disputed(s1.getId(), "solo", "reason", null);

        long kpi = findings.countDistinctDisputed();
        long clusterSum = clusters.computeClusters().stream().mapToInt(DisputeCluster::count).sum();

        // +2 DISTINCT disputed fingerprints (recurring + solo), not +3. Drop DISTINCT from the KPI and this is +3.
        assertThat(kpi - baseKpi).isEqualTo(2);
        // The section total the dashboard shows must always equal the KPI — both move by the same delta.
        assertThat(clusterSum - baseClusterSum).isEqualTo(kpi - baseKpi);
    }

    @Test
    void findsAPriorDisputeVerdictEvenWhileTheFindingIsStillOpen() {
        Scan first = scan("svc-a");
        FindingRecord f = disputed(first.getId(), "v1", "reason", "CONFIRMED_FP");
        assertThat(f.getStatus()).isNull();   // OPEN — the status-based disposition lookup would miss this verdict

        List<FindingRecord> priors = findings.findPriorDisputeVerdicts(List.of("v1"), "some-other-scan");
        assertThat(priors).extracting(FindingRecord::getFingerprint).contains("v1");
        assertThat(priors.get(0).getDisputeVerdict()).isEqualTo("CONFIRMED_FP");
    }

    private Scan scan(String service) {
        Scan s = new Scan();
        s.setServiceName(service);
        return scans.save(s);
    }

    private FindingRecord disputed(String scanId, String fingerprint, String reason, String verdict) {
        FindingRecord f = new FindingRecord();
        f.setScanId(scanId);
        f.setType("PARAM_TYPE_MISMATCH");
        f.setFingerprint(fingerprint);
        f.setSeverity("MAJOR");
        f.setAiDisputed(true);
        f.setAiDisputeReason(reason);
        f.setDisputeVerdict(verdict);
        return findings.save(f);
    }

    private void dismissed(String scanId, String fingerprint) {
        FindingRecord f = new FindingRecord();
        f.setScanId(scanId);
        f.setType("PARAM_TYPE_MISMATCH");
        f.setFingerprint(fingerprint);
        f.setSeverity("MAJOR");
        f.setAiDisputed(true);
        f.setStatus("FALSE_POSITIVE");
        findings.save(f);
    }
}
