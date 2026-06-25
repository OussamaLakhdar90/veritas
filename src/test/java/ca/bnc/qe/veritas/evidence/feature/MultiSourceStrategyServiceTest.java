package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.Set;
import ca.bnc.qe.veritas.evidence.ExtractionResult;
import ca.bnc.qe.veritas.evidence.FetchProvenance;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The wire-up: build → assemble → persist a TestStrategy; the §1.3 hard-fail gate stops before any synthesis spend. */
class MultiSourceStrategyServiceTest {

    private final FeatureIndexBuilder builder = mock(FeatureIndexBuilder.class);
    private final MultiSourceStrategyAssembler assembler = mock(MultiSourceStrategyAssembler.class);
    private final TestStrategyRepository repository = mock(TestStrategyRepository.class);
    private final ObjectMapper om = new ObjectMapper();   // declared before service (field initializers run in order)
    private final MultiSourceStrategyService service =
            new MultiSourceStrategyService(builder, assembler, new StrategyScorecardEngine(), repository, om);

    private FeatureIndexResult indexResult(boolean hardFail) {
        FetchProvenance prov = new FetchProvenance(Map.of(SourceKind.JIRA,
                new FetchProvenance.Counts(1, hardFail ? 0 : 1, hardFail ? List.of("nothing fetched") : List.of())));
        ExtractionResult ex = new ExtractionResult(List.of(), prov, new SourceMix(false, true, false), 0, Set.of(SourceKind.JIRA));
        FeatureIndex idx = new FeatureIndex(Map.of(), Map.of(), Set.of(), Set.of(), new SourceMix(false, true, false), "src");
        return new FeatureIndexResult(idx, new GapReport(List.of(), Set.of()), ex);
    }

    @Test
    void buildsAssemblesAndPersistsTheStrategy() {
        when(builder.build(any(), anyString())).thenReturn(indexResult(false));
        ObjectNode deliverable = om.createObjectNode();
        deliverable.put("summary", "Multi-source strategy");
        deliverable.set("gaps", om.createArrayNode());
        when(assembler.assemble(anyString(), any(), anyString(), any()))
                .thenReturn(new AssembledStrategy(deliverable, List.of(), 2, 0.21));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        TestStrategy s = service.generate("ciam-policies", SourceSelection.ofJira("project = CIAM", 50), "alice");

        assertThat(s.getSource()).isEqualTo("multi-source");
        assertThat(s.getStatus()).isEqualTo("DRAFT");
        assertThat(s.getServiceName()).isEqualTo("ciam-policies");
        assertThat(s.getEstCostUsd()).isCloseTo(0.21, within(1e-9));   // the run cost is persisted on the strategy
        assertThat(s.getDeliverableJson()).contains("Multi-source strategy");
        assertThat(s.getContentMarkdown()).contains("Test Strategy — ciam-policies");
        assertThat(s.getLineageId()).isEqualTo(s.getId());             // v1 seeds its own lineage
        // The deterministic scorecard is computed + persisted: an empty index passes every check → OK / 100.
        assertThat(s.getScorecardJson()).contains(StrategyScorecard.OK);
        assertThat(s.getConfidence()).isEqualTo(100.0);
        verify(assembler).assemble(eq("ciam-policies"), any(), eq("alice"), any());
    }

    @Test
    void aHardFailedSourceStopsBeforeSynthesisOrPersistence() {
        when(builder.build(any(), anyString())).thenReturn(indexResult(true));
        assertThatThrownBy(() -> service.generate("svc", SourceSelection.ofJira("project = NONE", 50), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no usable evidence");
        verify(assembler, never()).assemble(anyString(), any(), anyString(), any());   // no paid synthesis
        verify(repository, never()).save(any());
    }

    private void stubAssembleAndSave() {
        ObjectNode deliverable = om.createObjectNode();
        deliverable.put("summary", "s");
        deliverable.set("gaps", om.createArrayNode());
        when(assembler.assemble(anyString(), any(), anyString(), any()))
                .thenReturn(new AssembledStrategy(deliverable, List.of(), 1, 0.0));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void theReuseOverloadPassesAPriorContextToTheAssembler() {
        stubAssembleAndSave();
        TestStrategy prior = new TestStrategy();
        prior.setDeliverableJson("{\"riskRegister\":[]}");
        FeatureIndexResult index = indexResult(false);

        service.generateFromIndex("svc", index, "alice", index, prior);

        ArgumentCaptor<MultiSourceStrategyAssembler.ReuseContext> cap =
                ArgumentCaptor.forClass(MultiSourceStrategyAssembler.ReuseContext.class);
        verify(assembler).assemble(eq("svc"), any(), eq("alice"), cap.capture());
        assertThat(cap.getValue()).isNotNull();
        assertThat(cap.getValue().priorDeliverable().has("riskRegister")).isTrue();
    }

    @Test
    void anUnparseablePriorDeliverableFallsBackToFullSynthesis() {
        stubAssembleAndSave();
        TestStrategy prior = new TestStrategy();
        prior.setDeliverableJson("{not valid json");
        FeatureIndexResult index = indexResult(false);

        service.generateFromIndex("svc", index, "alice", index, prior);   // must not throw

        ArgumentCaptor<MultiSourceStrategyAssembler.ReuseContext> cap =
                ArgumentCaptor.forClass(MultiSourceStrategyAssembler.ReuseContext.class);
        verify(assembler).assemble(eq("svc"), any(), eq("alice"), cap.capture());
        assertThat(cap.getValue()).isNull();   // corrupt prior → reuse declined, full synthesis
    }
}
