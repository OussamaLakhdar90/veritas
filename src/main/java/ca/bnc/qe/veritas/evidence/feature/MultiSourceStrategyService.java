package ca.bnc.qe.veritas.evidence.feature;

import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The terminal wire-up of the multi-source pipeline (design §4): run the evidence pipeline
 * ({@link FeatureIndexBuilder}) → assemble the evidence-first strategy ({@link MultiSourceStrategyAssembler}) →
 * persist it as a {@link TestStrategy}. The single call a controller / CLI invokes to produce a multi-source
 * strategy from any combination of Jira + Confluence + code.
 *
 * <p>Honours the §1.3 hard-fail gate at the service boundary: if a <b>selected</b> source returned no usable
 * evidence, it stops <b>before</b> the (paid) synthesis and surfaces the failure, rather than persisting a thin
 * strategy built on a half-empty corpus.
 */
@Service
@Slf4j
public class MultiSourceStrategyService {

    private final FeatureIndexBuilder featureIndexBuilder;
    private final MultiSourceStrategyAssembler assembler;
    private final TestStrategyRepository repository;

    public MultiSourceStrategyService(FeatureIndexBuilder featureIndexBuilder, MultiSourceStrategyAssembler assembler,
                                      TestStrategyRepository repository) {
        this.featureIndexBuilder = featureIndexBuilder;
        this.assembler = assembler;
        this.repository = repository;
    }

    /** Extract → build the feature index → synthesize → persist, in one shot (no editable preview snapshot). */
    public TestStrategy generate(String serviceName, SourceSelection selection, String owner) {
        return generateFromIndex(serviceName, featureIndexBuilder.build(selection, owner), owner);
    }

    /**
     * Synthesize + persist from an already-built {@link FeatureIndexResult} — the §6 path where the index was
     * previewed (and possibly edited) once and is reused on generate, instead of re-running the whole pipeline.
     * Honours the §1.3 hard-fail gate here too: the persisted extraction carries the provenance, so a snapshot of
     * a half-empty corpus is refused before any (paid) synthesis, exactly as the one-shot path is.
     */
    public TestStrategy generateFromIndex(String serviceName, FeatureIndexResult index, String owner) {
        if (index.hasHardFail()) {
            // A selected source returning nothing is a caller-input problem → IllegalArgumentException (maps to 400),
            // mirroring how the rest of the codebase signals preconditions. Stops before any (paid) synthesis (§1.3).
            throw new IllegalArgumentException("A selected source returned no usable evidence "
                    + index.fetchFailures() + " — fix or deselect it before generating a strategy.");
        }

        AssembledStrategy assembled = assembler.assemble(serviceName, index, owner);

        TestStrategy strategy = new TestStrategy();   // id is assigned at construction (UUID)
        strategy.setServiceName(serviceName);
        strategy.setDeliverableJson(assembled.deliverable().toString());
        strategy.setContentMarkdown(markdown(serviceName, assembled));
        strategy.setEstCostUsd(assembled.estCostUsd());
        strategy.setStatus("DRAFT");
        strategy.setSource("multi-source");
        strategy.setOwner(owner);
        strategy.setVersion(1);
        strategy.setLineageId(strategy.getId());      // v1 seeds its own lineage
        TestStrategy saved = repository.save(strategy);
        log.info("Persisted multi-source strategy {} for {}: {} feature(s), {} dropped, ${}",
                saved.getId(), serviceName, assembled.featuresCovered(), assembled.droppedSections().size(),
                String.format("%.4f", assembled.estCostUsd()));
        return saved;
    }

    /** A short deterministic markdown projection (the deliverable JSON holds the full structured strategy). */
    private String markdown(String serviceName, AssembledStrategy a) {
        StringBuilder md = new StringBuilder("# Test Strategy — ").append(serviceName).append("\n\n");
        md.append(a.deliverable().path("summary").asText("")).append("\n\n");
        md.append("## Coverage\n")
                .append(a.featuresCovered()).append(" feature(s); ")
                .append(a.deliverable().path("gaps").size()).append(" gap(s). Est. cost $")
                .append(String.format("%.4f", a.estCostUsd())).append(".\n");
        if (!a.droppedSections().isEmpty()) {
            md.append("\n_").append(a.droppedSections().size())
                    .append(" section(s) could not be grounded in the evidence and were omitted._\n");
        }
        return md.toString();
    }
}
