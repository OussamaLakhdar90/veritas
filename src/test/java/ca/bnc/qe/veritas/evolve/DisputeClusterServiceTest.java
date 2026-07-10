package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository.DisputeRow;
import org.junit.jupiter.api.Test;

class DisputeClusterServiceTest {

    private final FindingRecordRepository repo = mock(FindingRecordRepository.class);
    private final DisputeClusterService service = new DisputeClusterService(repo);

    @Test
    void groupsByTypeDedupesByFingerprintCountsServicesAndTalliesVerdicts() {
        // fp1 was re-disputed on a later scan (rows arrive newest-first) — the stale duplicate is dropped and the
        // NEWEST row's verdict/service win. fp2 is a second PARAM_TYPE_MISMATCH. One SECURITY_MISMATCH with a null
        // service (counted, but not a distinct service, and no verdict). One unknown type → skipped.
        // Build every row mock BEFORE stubbing the repo, or the inner when(...) trips UnfinishedStubbingException.
        DisputeRow p1New = row("id-p1b", "PARAM_TYPE_MISMATCH", "fp1", "int32 vs integer", "NEEDS_DETECTION_FIX",
                "svc-b", "s2", "GET /a", "type mismatch");
        DisputeRow p1Old = row("id-p1a", "PARAM_TYPE_MISMATCH", "fp1", "stale reason", null, "svc-a", "s1", "GET /a",
                "type mismatch");
        DisputeRow p2 = row("id-p2", "PARAM_TYPE_MISMATCH", "fp2", "ref vs inline", "CONFIRMED_FP", "svc-a", "s1",
                "GET /b", "type mismatch");
        DisputeRow sec = row("id-sec", "SECURITY_MISMATCH", "fp3", "optional security", null, null, "s1", "GET /c",
                "security");
        DisputeRow unknown = row("id-x", "NOT_A_REAL_TYPE", "fp4", "x", null, "svc-a", "s1", "GET /d", "x");
        when(repo.findDisputedRows()).thenReturn(List.of(p1New, p1Old, p2, sec, unknown));

        List<DisputeCluster> clusters = service.computeClusters();

        // Unknown type dropped; PARAM_TYPE_MISMATCH (2 disputes) leads SECURITY_MISMATCH (1).
        assertThat(clusters).extracting(DisputeCluster::findingType)
                .containsExactly(FindingType.PARAM_TYPE_MISMATCH, FindingType.SECURITY_MISMATCH);

        DisputeCluster param = clusters.get(0);
        assertThat(param.count()).isEqualTo(2);                 // fp1 (deduped) + fp2
        assertThat(param.distinctServices()).isEqualTo(2);      // svc-b (fp1's newest row) + svc-a (fp2)
        assertThat(param.verdictBreakdown())
                .containsEntry("NEEDS_DETECTION_FIX", 1)         // the NEWEST fp1 row won, not the null-verdict stale one
                .containsEntry("CONFIRMED_FP", 1);
        assertThat(param.examples()).extracting(DisputeCluster.Example::verdict)
                .containsExactly("NEEDS_DETECTION_FIX", "CONFIRMED_FP");
        assertThat(param.examples().get(0).service()).isEqualTo("svc-b");

        DisputeCluster security = clusters.get(1);
        assertThat(security.count()).isEqualTo(1);
        assertThat(security.distinctServices()).isEqualTo(0);   // null service → not counted as distinct
        assertThat(security.verdictBreakdown()).isEmpty();      // no verdict recorded yet
    }

    @Test
    void capsExamplesPerTypeButCountsEveryDispute() {
        List<DisputeRow> rows = new ArrayList<>();
        int n = DisputeClusterService.MAX_EXAMPLES + 3;
        for (int i = 0; i < n; i++) {
            rows.add(row("id" + i, "STATUS_CODE_MISSING", "fp" + i, "r" + i, null, "svc-" + i, "s" + i, "GET /" + i,
                    "s"));
        }
        when(repo.findDisputedRows()).thenReturn(rows);

        List<DisputeCluster> clusters = service.computeClusters();
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).count()).isEqualTo(n);                              // every dispute counted
        assertThat(clusters.get(0).examples()).hasSize(DisputeClusterService.MAX_EXAMPLES);  // examples capped
    }

    @Test
    void emptyWhenNothingDisputed() {
        when(repo.findDisputedRows()).thenReturn(List.of());
        assertThat(service.computeClusters()).isEmpty();
    }

    private static DisputeRow row(String id, String type, String fingerprint, String reason, String verdict,
                                  String service, String scanId, String endpoint, String summary) {
        DisputeRow r = mock(DisputeRow.class);
        when(r.getId()).thenReturn(id);
        when(r.getType()).thenReturn(type);
        when(r.getFingerprint()).thenReturn(fingerprint);
        when(r.getReason()).thenReturn(reason);
        when(r.getVerdict()).thenReturn(verdict);
        when(r.getService()).thenReturn(service);
        when(r.getScanId()).thenReturn(scanId);
        when(r.getEndpoint()).thenReturn(endpoint);
        when(r.getSummary()).thenReturn(summary);
        return r;
    }
}
