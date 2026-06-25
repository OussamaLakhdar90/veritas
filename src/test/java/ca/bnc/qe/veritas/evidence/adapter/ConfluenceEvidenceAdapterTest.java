package ca.bnc.qe.veritas.evidence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.ingest.ConfluenceStorageToMarkdown;
import ca.bnc.qe.veritas.ingest.NormalizedDoc;
import ca.bnc.qe.veritas.ingest.TestBasisExtractor;
import ca.bnc.qe.veritas.ingest.TestBasisItem;
import ca.bnc.qe.veritas.ingest.TestBasisKind;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import org.junit.jupiter.api.Test;

/** Confluence pages → DESIGN units with section-anchored content-hash ids; a failed page is contained. */
class ConfluenceEvidenceAdapterTest {

    private final ConfluenceClient confluence = mock(ConfluenceClient.class);
    private final ConfluenceStorageToMarkdown storage = mock(ConfluenceStorageToMarkdown.class);
    private final TestBasisExtractor extractor = mock(TestBasisExtractor.class);
    private final ConfluenceEvidenceAdapter adapter = new ConfluenceEvidenceAdapter(confluence, storage, extractor);

    @Test
    void mapsPagesToDesignUnitsAndContainsAFailedPage() {
        when(confluence.getPage("PAGE-1")).thenReturn(new ConfluencePage("PAGE-1", "Auth design", "<xhtml/>"));
        when(confluence.getPage("PAGE-2")).thenThrow(new RuntimeException("500"));
        when(storage.normalize(any(), any(), any()))
                .thenReturn(new NormalizedDoc("confluence", "PAGE-1", "Auth design", "# Lockout\nLocks after 5."));
        when(extractor.extract(any())).thenReturn(List.of(
                new TestBasisItem("PAGE-1#lockout-1", "PAGE-1", TestBasisKind.BUSINESS_RULE, "Account locks after 5 attempts.")));

        SourceExtraction r = adapter.extract(List.of("PAGE-1", "PAGE-2"));

        assertThat(r.requested()).isEqualTo(2);
        assertThat(r.fetched()).isEqualTo(1);
        assertThat(r.failed()).anyMatch(s -> s.startsWith("PAGE-2:"));
        assertThat(r.units()).hasSize(1);
        assertThat(r.units().get(0).source()).isEqualTo(SourceKind.CONFLUENCE);
        assertThat(r.units().get(0).type()).isEqualTo(UnitType.DESIGN);   // Confluence is always DESIGN
        assertThat(r.units().get(0).id()).startsWith("PAGE-1#lockout-");
        assertThat(r.units().get(0).text()).contains("locks after 5");
    }

    @Test
    void anchorRecoversTheSectionSlugFromABasisItemId() {
        assertThat(ConfluenceEvidenceAdapter.anchorOf("PAGE-1#auth-flow-3", "PAGE-1")).isEqualTo("auth-flow");
        assertThat(ConfluenceEvidenceAdapter.anchorOf("PAGE-1", "PAGE-1")).isEqualTo("section");
    }
}
