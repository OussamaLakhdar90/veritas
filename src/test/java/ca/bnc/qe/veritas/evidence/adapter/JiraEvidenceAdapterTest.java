package ca.bnc.qe.veritas.evidence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.ingest.AdfToMarkdown;
import ca.bnc.qe.veritas.ingest.NormalizedDoc;
import ca.bnc.qe.veritas.ingest.TestBasisExtractor;
import ca.bnc.qe.veritas.ingest.TestBasisItem;
import ca.bnc.qe.veritas.ingest.TestBasisKind;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import org.junit.jupiter.api.Test;

/** Jira issues → intent units; one malformed issue is contained (atomic); a search failure is recorded, not thrown. */
class JiraEvidenceAdapterTest {

    private final JiraClient jira = mock(JiraClient.class);
    private final AdfToMarkdown adf = mock(AdfToMarkdown.class);
    private final TestBasisExtractor extractor = mock(TestBasisExtractor.class);
    private final JiraEvidenceAdapter adapter = new JiraEvidenceAdapter(jira, adf, extractor);

    @Test
    void mapsSummaryToRequirementAndDescriptionToAcAndContainsAFailingIssue() {
        JiraIssue ok = new JiraIssue("CIAM-1", "Get policy by app id", null);
        JiraIssue bad = new JiraIssue("CIAM-2", "Delete policy", null);
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(ok, bad));
        when(extractor.extract(any())).thenAnswer(inv -> {
            NormalizedDoc d = inv.getArgument(0);
            if ("CIAM-1".equals(d.sourceId())) {
                return List.of(new TestBasisItem("CIAM-1#ac-1", "CIAM-1",
                        TestBasisKind.ACCEPTANCE_CRITERIA, "returns 200 with the policy"));
            }
            throw new RuntimeException("boom");   // CIAM-2 blows up mid-processing
        });

        SourceExtraction r = adapter.extract("project = CIAM", 50);

        assertThat(r.kind()).isEqualTo(SourceKind.JIRA);
        assertThat(r.requested()).isEqualTo(2);
        assertThat(r.fetched()).isEqualTo(1);                       // only CIAM-1 fully succeeded
        assertThat(r.failed()).anyMatch(s -> s.startsWith("CIAM-2:"));
        // CIAM-1: an issue-level REQUIREMENT + an ACCEPTANCE_CRITERIA; CIAM-2 contributed nothing (atomic).
        assertThat(r.units()).extracting(u -> u.id())
                .contains("CIAM-1").noneMatch(id -> id.startsWith("CIAM-2"));
        assertThat(r.units()).anyMatch(u -> u.type() == UnitType.REQUIREMENT && u.id().equals("CIAM-1"));
        assertThat(r.units()).anyMatch(u -> u.type() == UnitType.ACCEPTANCE_CRITERIA);
    }

    @Test
    void aSearchFailureIsRecordedAsAnEmptyFetchNotThrown() {
        when(jira.search(any(), any(), anyInt())).thenThrow(new RuntimeException("connection refused"));
        SourceExtraction r = adapter.extract("project = CIAM", 50);
        assertThat(r.units()).isEmpty();
        assertThat(r.requested()).isEqualTo(1);   // selected but empty → §1.3 hard-fail signal
        assertThat(r.fetched()).isZero();
        assertThat(r.failed()).anyMatch(s -> s.contains("connection refused"));
    }
}
