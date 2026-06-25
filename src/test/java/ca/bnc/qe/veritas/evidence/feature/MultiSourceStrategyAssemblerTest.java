package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Assembly: per-feature sections merged into the flat deliverable, gaps folded in, cost summed, drops tracked. */
class MultiSourceStrategyAssemblerTest {

    private final EvidenceFirstSectionGenerator generator = mock(EvidenceFirstSectionGenerator.class);
    private final ObjectMapper om = new ObjectMapper();
    private final MultiSourceStrategyAssembler assembler =
            new MultiSourceStrategyAssembler(generator, new EvidenceRetriever(), om);

    private JsonNode json(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private FeatureIndexResult oneImplementedFeature() {
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "t", "x", null, Set.of());
        EvidenceUnit code = EvidenceUnit.of("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "t", "x", null, Set.of());
        FeatureIndex index = new FeatureIndex(
                Map.of("feat-1", new Feature("feat-1", "login", List.of("JIRA-1", "CODE-1"), FeatureStatus.IMPLEMENTED)),
                Map.of("JIRA-1", jira, "CODE-1", code), Set.of(), Set.of(), new SourceMix(true, true, false), "src");
        GapReport gaps = new GapReport(
                List.of(new Gap(GapKind.IMPLEMENTED_UNDOCUMENTED, "feat-1", "implemented but unspecified", List.of("CODE-1"))),
                Set.of("feat-1"));
        ExtractionResult ex = new ExtractionResult(List.of(), new FetchProvenance(Map.of()),
                new SourceMix(true, true, false), 0, Set.of());
        return new FeatureIndexResult(index, gaps, ex);
    }

    @Test
    void mergesPerFeatureSections_foldsGaps_sumsCost_andTracksDrops() {
        // riskRegister grounds ($0.10); testApproach is dropped ($0.02 still spent).
        when(generator.generate(eq("riskRegister"), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new SectionResult(Optional.of(
                        json("{\"feature\":\"(llm)\",\"evidence\":[{\"unitId\":\"JIRA-1\"}],\"content\":[{\"id\":\"R1\"}]}")), 0.10));
        when(generator.generate(eq("testApproach"), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new SectionResult(Optional.empty(), 0.02));

        AssembledStrategy s = assembler.assemble("ciam-policies", oneImplementedFeature(), "alice");

        // The risk section lands in the flat array, tagged with the canonical feature name + status.
        assertThat(s.deliverable().path("riskRegister")).hasSize(1);
        assertThat(s.deliverable().path("riskRegister").get(0).path("feature").asText()).isEqualTo("login");
        assertThat(s.deliverable().path("riskRegister").get(0).path("featureStatus").asText()).isEqualTo("IMPLEMENTED");
        // testApproach was dropped, not aborted.
        assertThat(s.deliverable().has("testApproach")).isFalse();
        assertThat(s.droppedSections()).containsExactly("testApproach / login");
        // Gaps folded in deterministically.
        assertThat(s.deliverable().path("gaps")).hasSize(1);
        assertThat(s.deliverable().path("gaps").get(0).path("kind").asText()).isEqualTo("IMPLEMENTED_UNDOCUMENTED");
        assertThat(s.deliverable().path("gaps").get(0).path("citedUnitIds").get(0).asText()).isEqualTo("CODE-1");
        // Cost summed across every section (including the dropped one) onto the strategy.
        assertThat(s.estCostUsd()).isCloseTo(0.12, within(1e-9));
        assertThat(s.deliverable().path("estCostUsd").asDouble()).isCloseTo(0.12, within(1e-9));
        assertThat(s.deliverable().path("summary").asText())
                .contains("ciam-policies").contains("1 feature(s)").contains("1 implemented");
        assertThat(s.featuresCovered()).isEqualTo(1);
    }

    // ---- incremental reuse (lineage re-run) -----------------------------------------------------

    /** A feature whose only Jira unit carries {@code jiraText}, named {@code name}, with {@code status}. */
    private FeatureIndexResult feature(String name, FeatureStatus status, String jiraText) {
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "t", jiraText, null, Set.of());
        EvidenceUnit code = EvidenceUnit.of("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "t", "x", null, Set.of());
        FeatureIndex index = new FeatureIndex(
                Map.of("feat-1", new Feature("feat-1", name, List.of("JIRA-1", "CODE-1"), status)),
                Map.of("JIRA-1", jira, "CODE-1", code), Set.of(), Set.of(), new SourceMix(true, true, false), "src");
        ExtractionResult ex = new ExtractionResult(List.of(), new FetchProvenance(Map.of()),
                new SourceMix(true, true, false), 0, Set.of());
        return new FeatureIndexResult(index, new GapReport(List.of(), Set.of()), ex);
    }

    /** A prior deliverable with a riskRegister + testApproach node for feat-1 (named login/IMPLEMENTED). */
    private JsonNode priorDeliverable() {
        return json("{\"riskRegister\":[{\"featureId\":\"feat-1\",\"feature\":\"login\",\"featureStatus\":\"IMPLEMENTED\","
                + "\"evidence\":[{\"unitId\":\"JIRA-1\"}],\"content\":[{\"id\":\"R-prior\"}]}],"
                + "\"testApproach\":[{\"featureId\":\"feat-1\",\"feature\":\"login\",\"featureStatus\":\"IMPLEMENTED\","
                + "\"evidence\":[{\"unitId\":\"JIRA-1\"}],\"content\":[{\"id\":\"A-prior\"}]}]}");
    }

    @Test
    void reusesAnUnchangedFeaturesSectionsVerbatimWithoutCallingTheGenerator() {
        var reuse = new MultiSourceStrategyAssembler.ReuseContext(feature("login", FeatureStatus.IMPLEMENTED, "x"),
                priorDeliverable());

        AssembledStrategy s = assembler.assemble("svc", feature("login", FeatureStatus.IMPLEMENTED, "x"), "alice", reuse);

        verify(generator, never()).generate(any(), any(), any(), any(), any(), any(), any());   // no LLM spend
        assertThat(s.reusedSections()).isEqualTo(2);
        assertThat(s.estCostUsd()).isEqualTo(0.0);
        assertThat(s.deliverable().path("riskRegister").get(0).path("content").get(0).path("id").asText()).isEqualTo("R-prior");
        assertThat(s.deliverable().path("testApproach").get(0).path("content").get(0).path("id").asText()).isEqualTo("A-prior");
    }

    @Test
    void resynthesizesAFeatureWhoseGroundingTextChanged() {
        when(generator.generate(eq("riskRegister"), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new SectionResult(Optional.of(json("{\"content\":[{\"id\":\"R-fresh\"}]}")), 0.10));
        when(generator.generate(eq("testApproach"), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new SectionResult(Optional.of(json("{\"content\":[{\"id\":\"A-fresh\"}]}")), 0.02));
        var reuse = new MultiSourceStrategyAssembler.ReuseContext(feature("login", FeatureStatus.IMPLEMENTED, "x"),
                priorDeliverable());

        // Same feature id, but the unit's TEXT changed → the grounding fingerprint differs → regenerate, don't reuse.
        AssembledStrategy s = assembler.assemble("svc", feature("login", FeatureStatus.IMPLEMENTED, "changed"), "alice", reuse);

        assertThat(s.reusedSections()).isZero();
        assertThat(s.estCostUsd()).isCloseTo(0.12, within(1e-9));
        assertThat(s.deliverable().path("riskRegister").get(0).path("content").get(0).path("id").asText()).isEqualTo("R-fresh");
    }

    /** feat-1 (login/IMPLEMENTED) plus a cross-cutting caveat unit whose text is {@code caveatText}. */
    private FeatureIndexResult featureWithCaveat(String caveatText) {
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "t", "x", null, Set.of());
        EvidenceUnit code = EvidenceUnit.of("CODE-1", SourceKind.CODE, UnitType.ENDPOINT, "t", "x", null, Set.of());
        EvidenceUnit caveat = EvidenceUnit.of("CAVEAT-1", SourceKind.POLICY, UnitType.STANDARD, "t", caveatText, null, Set.of());
        FeatureIndex index = new FeatureIndex(
                Map.of("feat-1", new Feature("feat-1", "login", List.of("JIRA-1", "CODE-1"), FeatureStatus.IMPLEMENTED)),
                Map.of("JIRA-1", jira, "CODE-1", code, "CAVEAT-1", caveat),
                Set.of("CAVEAT-1"), Set.of(), new SourceMix(true, true, false), "src");   // CAVEAT-1 is cross-cutting
        ExtractionResult ex = new ExtractionResult(List.of(), new FetchProvenance(Map.of()),
                new SourceMix(true, true, false), 0, Set.of());
        return new FeatureIndexResult(index, new GapReport(List.of(), Set.of()), ex);
    }

    @Test
    void aChangedCrossCuttingUnitInvalidatesEveryFeaturesReuse() {
        when(generator.generate(eq("riskRegister"), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new SectionResult(Optional.of(json("{\"content\":[{\"id\":\"R-fresh\"}]}")), 0.10));
        when(generator.generate(eq("testApproach"), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new SectionResult(Optional.of(json("{\"content\":[{\"id\":\"A-fresh\"}]}")), 0.02));
        var reuse = new MultiSourceStrategyAssembler.ReuseContext(featureWithCaveat("caveat v1"), priorDeliverable());

        // The feature's OWN units are unchanged, but a cross-cutting caveat (injected into every slice) changed →
        // the fingerprint flips → the feature must be re-synthesised, never reused with a stale caveat.
        AssembledStrategy s = assembler.assemble("svc", featureWithCaveat("caveat v2"), "alice", reuse);

        assertThat(s.reusedSections()).isZero();
        verify(generator).generate(eq("riskRegister"), anyString(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void aReusedNodeIsReStampedWithTheCurrentFeatureNameAndStatus() {
        var reuse = new MultiSourceStrategyAssembler.ReuseContext(feature("login", FeatureStatus.IMPLEMENTED, "x"),
                priorDeliverable());

        // The feature was renamed login→sign-in and flipped IMPLEMENTED→PARTIAL, but its units + text are identical,
        // so the section body is reused ($0) — yet re-stamped with today's name/status.
        AssembledStrategy s = assembler.assemble("svc", feature("sign-in", FeatureStatus.PARTIAL, "x"), "alice", reuse);

        assertThat(s.reusedSections()).isEqualTo(2);
        JsonNode node = s.deliverable().path("riskRegister").get(0);
        assertThat(node.path("feature").asText()).isEqualTo("sign-in");          // re-stamped name
        assertThat(node.path("featureStatus").asText()).isEqualTo("PARTIAL");    // re-stamped status
        assertThat(node.path("content").get(0).path("id").asText()).isEqualTo("R-prior");   // body still reused
    }
}
