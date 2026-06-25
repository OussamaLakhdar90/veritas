package ca.bnc.qe.veritas.evidence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.ingest.AdfToMarkdown;
import ca.bnc.qe.veritas.ingest.NormalizedDoc;
import ca.bnc.qe.veritas.ingest.TestBasisExtractor;
import ca.bnc.qe.veritas.ingest.TestBasisItem;
import ca.bnc.qe.veritas.ingest.TestBasisKind;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Jira issues → intent units; one malformed issue is contained (atomic); a search failure is recorded, not thrown. */
class JiraEvidenceAdapterTest {

    private final JiraClient jira = mock(JiraClient.class);
    private final AdfToMarkdown adf = mock(AdfToMarkdown.class);
    private final TestBasisExtractor extractor = mock(TestBasisExtractor.class);
    private final JiraEvidenceAdapter adapter = new JiraEvidenceAdapter(jira, adf, extractor);

    @Test
    void mapsSummaryToRequirementAndDescriptionToAcAndContainsAFailingIssue() {
        JiraIssue ok = JiraIssue.basic("CIAM-1", "Get policy by app id", null);
        JiraIssue bad = JiraIssue.basic("CIAM-2", "Delete policy", null);
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
    void unitsCarryLifecyclePriorityLinksAndComponentHintsButNotLabels() {
        JiraIssue issue = new JiraIssue("CIAM-7", "Get policy", null,
                "DONE", "High", List.of("tech-debt"), List.of("Authentication"), List.of("CIAM-9"));
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(issue));
        when(extractor.extract(any())).thenReturn(List.of());   // no description-derived units

        SourceExtraction r = adapter.extract("project = CIAM", 50);

        assertThat(r.units()).hasSize(1);
        EvidenceUnit u = r.units().get(0);
        assertThat(u.lifecycle()).isEqualTo("DONE");
        assertThat(u.priority()).isEqualTo("High");
        assertThat(u.links()).containsExactly("CIAM-9");
        assertThat(u.hints()).contains("authentication");      // the component is a clustering hint
        assertThat(u.hints()).doesNotContain("tech-debt");     // a generic label is NOT (over-merge risk)
    }

    @Test
    void aServerDcWikiDescriptionIsUsedAsTextNotDroppedAsNonAdf() {
        JsonNode wiki = new ObjectMapper().valueToTree("h2. Acceptance\n* must return 200");   // a wiki-markup string
        JiraIssue issue = new JiraIssue("CIAM-8", "Get policy", wiki,
                "IN_PROGRESS", null, List.of(), List.of(), List.of());
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(issue));
        ArgumentCaptor<NormalizedDoc> doc = ArgumentCaptor.forClass(NormalizedDoc.class);
        when(extractor.extract(doc.capture())).thenReturn(List.of());

        adapter.extract("project = CIAM", 50);

        assertThat(doc.getValue().markdown()).contains("must return 200");   // wiki text reached the extractor
        verify(adf, never()).toMarkdown(any());   // a textual description is NOT run through the ADF converter
    }

    @Test
    void anAllDescopedReleaseContributesNoUsableFetchSoTheHardFailGateTrips() {
        JiraIssue descoped = new JiraIssue("CIAM-9", "Old idea", null,
                "DESCOPED", null, List.of(), List.of(), List.of());
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(descoped));

        SourceExtraction r = adapter.extract("project = CIAM", 50);

        assertThat(r.units()).isEmpty();              // descoped issues emit no units
        assertThat(r.requested()).isEqualTo(1);
        assertThat(r.fetched()).isZero();             // requested>0, fetched==0 → §1.3 hard-fail signal
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
