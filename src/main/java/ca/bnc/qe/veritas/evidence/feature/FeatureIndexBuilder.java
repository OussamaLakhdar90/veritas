package ca.bnc.qe.veritas.evidence.feature;

import ca.bnc.qe.veritas.evidence.EvidenceExtractor;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The end-to-end evidence pipeline (design §2–§3), wired into one entry point: <b>extract</b> (deterministic, $0)
 * → <b>seed</b> the feature clusters (deterministic, $0) → <b>tag</b> (one cheap ECONOMY LLM call that
 * canonicalises the seed, or the seed unchanged when no LLM) → <b>detect gaps</b> (deterministic, $0). Turns a
 * {@link SourceSelection} into the {@link FeatureIndexResult} the synthesis (Phase 4b) consumes.
 *
 * <p>The §1.3 hard-fail gate is honoured: when a <b>selected</b> source fetched nothing usable, the run must NOT
 * spend on the LLM tagger — it returns the deterministic seed so the caller can surface the failure before any cost.
 */
@Service
@Slf4j
public class FeatureIndexBuilder {

    private final EvidenceExtractor extractor;
    private final FeatureSeeder seeder;
    private final FeatureTagger tagger;
    private final GapDetector gapDetector;

    public FeatureIndexBuilder(EvidenceExtractor extractor, FeatureSeeder seeder, FeatureTagger tagger,
                               GapDetector gapDetector) {
        this.extractor = extractor;
        this.seeder = seeder;
        this.tagger = tagger;
        this.gapDetector = gapDetector;
    }

    public FeatureIndexResult build(SourceSelection selection, String owner) {
        ExtractionResult extraction = extractor.extract(selection);
        FeatureIndex seed = seeder.seed(extraction.units(), extraction.mix());
        // §1.3: don't spend the LLM tag on a hard-failed run (a selected source returned nothing usable).
        FeatureIndex index = extraction.hasHardFail() ? seed : tagger.tag(seed, owner);
        GapReport gaps = gapDetector.detect(index);
        log.info("Feature index built: {} feature(s), {} gap(s), {} redaction(s), mix(code={} jira={} confluence={}), hardFail={}",
                index.features().size(), gaps.gaps().size(), extraction.redactionCount(),
                index.mix().code(), index.mix().jira(), index.mix().confluence(), extraction.hasHardFail());
        return new FeatureIndexResult(index, gaps, extraction);
    }
}
