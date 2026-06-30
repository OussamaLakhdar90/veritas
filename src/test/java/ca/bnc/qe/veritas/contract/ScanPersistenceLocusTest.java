package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A human disposition survives a finding's summary (hence fingerprint) change via the type+endpoint+specSource locus —
 * but ONLY when that locus is unambiguous, so a disposition can never be carried onto the wrong finding.
 */
class ScanPersistenceLocusTest {

    private static String locus(FindingType type, String endpoint, String specSource) {
        return Integer.toHexString(Objects.hash(type.name(), endpoint, specSource));
    }

    private static Finding finding(String findingId, String summary) {
        return Finding.builder().findingId(findingId).type(FindingType.CONSTRAINT_GAP).severity(Severity.MAJOR)
                .origin("DETERMINISTIC").endpoint("GET /a").specSource("repo-spec").summary(summary).build();
    }

    private static FindingRecord prior(String fingerprint, String status) {
        FindingRecord r = new FindingRecord();
        r.setFingerprint(fingerprint);
        r.setLocusKey(locus(FindingType.CONSTRAINT_GAP, "GET /a", "repo-spec"));
        r.setStatus(status);
        r.setReviewedBy("alice");
        return r;
    }

    @SuppressWarnings("unchecked")
    private static FindingRecord saveAndCapture(FindingRecordRepository repo, ScanRepository scans,
                                                List<Finding> findings) {
        Scan scan = new Scan();
        scan.setId("scan-2");
        new ScanPersistence(scans, repo).complete(scan, findings, Map.<String, JsonNode>of());
        ArgumentCaptor<List<FindingRecord>> cap = ArgumentCaptor.forClass((Class) List.class);
        verify(repo).saveAll(cap.capture());
        return cap.getValue().stream().filter(r -> "new-fp".equals(r.getFingerprint())).findFirst().orElseThrow();
    }

    @Test
    void dispositionCarriesForwardViaLocusWhenSummaryChanged() {
        FindingRecordRepository repo = mock(FindingRecordRepository.class);
        ScanRepository scans = mock(ScanRepository.class);
        when(repo.findPriorDispositions(any(), any())).thenReturn(List.of());                  // no exact match
        when(repo.findPriorDispositionsByLocus(any(), any())).thenReturn(List.of(prior("old-fp", "REJECTED")));

        FindingRecord saved = saveAndCapture(repo, scans, List.of(finding("new-fp", "minLength code=5 spec=8")));

        assertThat(saved.getStatus()).isEqualTo("REJECTED");   // the prior dismissal survived the text change
        assertThat(saved.getReviewedBy()).isEqualTo("alice");
    }

    @Test
    void ambiguousLocusDoesNotCarryForward() {
        FindingRecordRepository repo = mock(FindingRecordRepository.class);
        ScanRepository scans = mock(ScanRepository.class);
        when(repo.findPriorDispositions(any(), any())).thenReturn(List.of());
        // two prior dispositions share the locus → can't tell which maps to the current finding → carry nothing
        when(repo.findPriorDispositionsByLocus(any(), any()))
                .thenReturn(List.of(prior("old-1", "REJECTED"), prior("old-2", "ACCEPTED")));

        FindingRecord saved = saveAndCapture(repo, scans, List.of(finding("new-fp", "minLength code=5 spec=8")));

        assertThat(saved.getStatus()).isEqualTo("OPEN");   // default — no ambiguous carry-forward
        assertThat(saved.getReviewedBy()).isNull();
    }

    @Test
    void multipleCurrentFindingsAtSameLocusDoNotCarryForward() {
        FindingRecordRepository repo = mock(FindingRecordRepository.class);
        ScanRepository scans = mock(ScanRepository.class);
        when(repo.findPriorDispositions(any(), any())).thenReturn(List.of());
        when(repo.findPriorDispositionsByLocus(any(), any())).thenReturn(List.of(prior("old-fp", "REJECTED")));

        // two current findings share the locus → a single prior can't be assigned to one of them → carry nothing
        FindingRecord saved = saveAndCapture(repo, scans,
                List.of(finding("new-fp", "minLength code=5 spec=8"), finding("sibling-fp", "maxLength code=9 spec=4")));

        assertThat(saved.getStatus()).isEqualTo("OPEN");
    }
}
