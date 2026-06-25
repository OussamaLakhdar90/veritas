package ca.bnc.qe.veritas.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.evidence.adapter.CodeEvidenceAdapter;
import ca.bnc.qe.veritas.evidence.adapter.ConfluenceEvidenceAdapter;
import ca.bnc.qe.veritas.evidence.adapter.JiraEvidenceAdapter;
import ca.bnc.qe.veritas.evidence.adapter.SourceExtraction;
import org.junit.jupiter.api.Test;

/** Orchestration: runs only selected sources, combines them, computes the mix from successes, and gates hard-fails. */
class EvidenceExtractorTest {

    private final CodeEvidenceAdapter code = mock(CodeEvidenceAdapter.class);
    private final JiraEvidenceAdapter jira = mock(JiraEvidenceAdapter.class);
    private final ConfluenceEvidenceAdapter confluence = mock(ConfluenceEvidenceAdapter.class);
    private final EvidenceExtractor extractor = new EvidenceExtractor(code, jira, confluence);

    private static EvidenceUnit u(String id, SourceKind k) {
        return EvidenceUnit.of(id, k, UnitType.REQUIREMENT, id, "text", null, Set.of());
    }

    private static ApiModel emptyModel() {
        return new ApiModel("code", "t", "1", null, List.of(), Map.of());
    }

    @Test
    void blendsAllThreeSourcesAndComputesMixFromSuccesses() {
        when(code.extract(any())).thenReturn(new SourceExtraction(SourceKind.CODE, List.of(u("CODE:1", SourceKind.CODE)), 1, 1, List.of(), 0));
        when(jira.extract(any(), anyInt())).thenReturn(new SourceExtraction(SourceKind.JIRA, List.of(u("JIRA-1", SourceKind.JIRA)), 3, 3, List.of(), 2));
        when(confluence.extract(any())).thenReturn(new SourceExtraction(SourceKind.CONFLUENCE, List.of(u("CONF#a-1", SourceKind.CONFLUENCE)), 2, 2, List.of(), 1));

        ExtractionResult r = extractor.extract(new SourceSelection(emptyModel(), "project = CIAM", 50, List.of("P1", "P2")));

        assertThat(r.units()).hasSize(3);
        assertThat(r.mix().code()).isTrue();
        assertThat(r.mix().jira()).isTrue();
        assertThat(r.mix().confluence()).isTrue();
        assertThat(r.redactionCount()).isEqualTo(3);
        assertThat(r.hasHardFail()).isFalse();
        assertThat(r.selected()).containsExactlyInAnyOrder(SourceKind.CODE, SourceKind.JIRA, SourceKind.CONFLUENCE);
    }

    @Test
    void runsOnlySelectedSources() {
        when(jira.extract(any(), anyInt())).thenReturn(new SourceExtraction(SourceKind.JIRA, List.of(u("JIRA-1", SourceKind.JIRA)), 1, 1, List.of(), 0));

        ExtractionResult r = extractor.extract(SourceSelection.ofJira("project = CIAM", 50));

        verify(code, never()).extract(any());
        verify(confluence, never()).extract(any());
        assertThat(r.mix().jira()).isTrue();
        assertThat(r.mix().code()).isFalse();
        assertThat(r.mix().confluence()).isFalse();
    }

    @Test
    void aSelectedSourceThatFetchesNothingIsAHardFail() {
        when(jira.extract(any(), anyInt())).thenReturn(new SourceExtraction(SourceKind.JIRA, List.of(), 1, 0, List.of("no issues for jql"), 0));
        ExtractionResult r = extractor.extract(SourceSelection.ofJira("project = NONE", 50));
        assertThat(r.hasHardFail()).isTrue();
        assertThat(r.fetchFailures()).contains("no issues for jql");
        assertThat(r.mix().any()).isFalse();
    }

    @Test
    void anAdapterThatThrowsIsContainedAsARecordedEmptyFetch() {
        when(confluence.extract(any())).thenThrow(new RuntimeException("confluence down"));
        ExtractionResult r = extractor.extract(SourceSelection.ofConfluence(List.of("P1")));
        assertThat(r.hasHardFail()).isTrue();   // backstop recorded requested=1, fetched=0
        assertThat(r.fetchFailures()).anyMatch(s -> s.contains("confluence down"));
    }
}
