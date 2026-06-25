package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceExtractor;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import org.junit.jupiter.api.Test;

/** Pipeline wiring: extract → seed → tag → detect, with the §1.3 hard-fail gate skipping the (paid) tagger. */
class FeatureIndexBuilderTest {

    private final EvidenceExtractor extractor = mock(EvidenceExtractor.class);
    private final FeatureSeeder seeder = mock(FeatureSeeder.class);
    private final FeatureTagger tagger = mock(FeatureTagger.class);
    private final GapDetector gapDetector = mock(GapDetector.class);
    private final FeatureIndexBuilder builder = new FeatureIndexBuilder(extractor, seeder, tagger, gapDetector);

    private final SourceMix mix = new SourceMix(false, true, false);
    private final FeatureIndex seedIndex = new FeatureIndex(Map.of(), Map.of(), Set.of(), Set.of(), mix, "src-seed");
    private final FeatureIndex taggedIndex = new FeatureIndex(Map.of(), Map.of(), Set.of(), Set.of(), mix, "src-tag");
    private final GapReport report = new GapReport(List.of(), Set.of());

    private static FetchProvenance jiraProvenance(int requested, int fetched) {
        return new FetchProvenance(Map.of(SourceKind.JIRA,
                new FetchProvenance.Counts(requested, fetched, fetched == 0 ? List.of("nothing fetched") : List.of())));
    }

    @Test
    void runsExtractSeedTagDetectAndReturnsTheBundle() {
        ExtractionResult ex = new ExtractionResult(List.of(), jiraProvenance(3, 3), mix, 2, Set.of(SourceKind.JIRA));
        when(extractor.extract(any())).thenReturn(ex);
        when(seeder.seed(any(), any())).thenReturn(seedIndex);
        when(tagger.tag(any(), anyString())).thenReturn(taggedIndex);
        when(gapDetector.detect(any())).thenReturn(report);

        FeatureIndexResult r = builder.build(SourceSelection.ofJira("project = CIAM", 50), "alice");

        assertThat(r.index()).isSameAs(taggedIndex);
        assertThat(r.gaps()).isSameAs(report);
        assertThat(r.redactionCount()).isEqualTo(2);
        assertThat(r.hasHardFail()).isFalse();
        verify(seeder).seed(List.of(), mix);
        verify(tagger).tag(seedIndex, "alice");
        verify(gapDetector).detect(taggedIndex);
    }

    @Test
    void aHardFailedRunSkipsThePaidTaggerAndReturnsTheSeed() {
        // Jira was selected but fetched nothing → §1.3 hard-fail: no LLM spend, gaps detected on the seed.
        ExtractionResult ex = new ExtractionResult(List.of(), jiraProvenance(1, 0), mix, 0, Set.of(SourceKind.JIRA));
        when(extractor.extract(any())).thenReturn(ex);
        when(seeder.seed(any(), any())).thenReturn(seedIndex);
        when(gapDetector.detect(any())).thenReturn(report);

        FeatureIndexResult r = builder.build(SourceSelection.ofJira("project = NONE", 50), "alice");

        assertThat(r.hasHardFail()).isTrue();
        assertThat(r.index()).isSameAs(seedIndex);
        verify(tagger, never()).tag(any(), anyString());
        verify(gapDetector).detect(seedIndex);
    }
}
