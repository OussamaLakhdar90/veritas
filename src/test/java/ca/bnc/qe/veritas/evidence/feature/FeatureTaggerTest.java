package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** The LLM canonicalisation: merges grouped seeds (cross-source), and degrades to the seed when the LLM can't help. */
class FeatureTaggerTest {

    private final LlmGateway llm = mock(LlmGateway.class);
    private final ModelSelector modelSelector = mock(ModelSelector.class);
    private final CostRecorder costRecorder = mock(CostRecorder.class);
    private final PromptComposer promptComposer = mock(PromptComposer.class);
    private final ResponseSchemaValidator schemaValidator = mock(ResponseSchemaValidator.class);

    private final FeatureTagger tagger = new FeatureTagger(llm, modelSelector, costRecorder, promptComposer,
            new JsonBlockExtractor(), schemaValidator, new ObjectMapper());

    private FeatureIndex twoSeedPolicyIndex() {
        EvidenceUnit jira = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Get policy", "t", null, Set.of());
        EvidenceUnit code = EvidenceUnit.of("CODE:PolicyController#GET /policies", SourceKind.CODE, UnitType.ENDPOINT,
                "GET /policies", "t", null, Set.of());
        Feature intent = new Feature("feat-intent", "policy", List.of("JIRA-1"), FeatureStatus.PLANNED);
        Feature impl = new Feature("feat-code", "policies", List.of("CODE:PolicyController#GET /policies"), FeatureStatus.UNDOCUMENTED);
        return new FeatureIndex(Map.of("feat-intent", intent, "feat-code", impl),
                Map.of("JIRA-1", jira, code.id(), code), Set.of(), Set.of(), new SourceMix(true, true, false), "src-abc");
    }

    private void stubComposer() {
        when(promptComposer.data(anyString(), anyString())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("PROMPT");
        when(modelSelector.resolveTier(any())).thenReturn("mock-model");
    }

    @Test
    void mergesTheIntentAndEndpointSeedsAcrossSourcesIntoOneImplementedFeature() {
        stubComposer();
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"features\":[{\"name\":\"Policy retrieval\",\"refs\":[\"feat-intent\",\"feat-code\"]}]}\n```");

        FeatureIndex result = tagger.tag(twoSeedPolicyIndex(), "alice");

        assertThat(result.features()).hasSize(1);
        Feature merged = result.features().values().iterator().next();
        assertThat(merged.displayName()).isEqualTo("Policy retrieval");
        assertThat(merged.unitIds()).containsExactlyInAnyOrder("JIRA-1", "CODE:PolicyController#GET /policies");
        assertThat(merged.status()).isEqualTo(FeatureStatus.IMPLEMENTED);   // intent + code, recomputed after merge
        assertThat(result.unassignedUnitIds()).isEmpty();
        verify(costRecorder).record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void returnsSeedUnchangedWhenLlmUnavailable() {
        when(llm.isAvailable()).thenReturn(false);
        FeatureIndex seed = twoSeedPolicyIndex();
        assertThat(tagger.tag(seed, "alice")).isSameAs(seed);
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void returnsSeedWhenThereIsNothingToMerge() {
        EvidenceUnit u = EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "x", "t", null, Set.of());
        FeatureIndex seed = new FeatureIndex(Map.of("feat-1", new Feature("feat-1", "x", List.of("JIRA-1"), FeatureStatus.PLANNED)),
                Map.of("JIRA-1", u), Set.of(), Set.of(), new SourceMix(false, true, false), "src-1");
        assertThat(tagger.tag(seed, "alice")).isSameAs(seed);   // <=1 feature → no call
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void degradesToTheSeedOnAnUnusableReply() {
        stubComposer();
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("sorry, I cannot help with that");   // no json block
        FeatureIndex seed = twoSeedPolicyIndex();
        assertThat(tagger.tag(seed, "alice")).isSameAs(seed);
    }

    @Test
    void unmergedSeedsAreKeptStandalone() {
        stubComposer();
        when(llm.isAvailable()).thenReturn(true);
        // The LLM merges only the two policy seeds; a third seed (if present) would survive. Here, group references
        // one real ref and one unknown ref — the unknown is ignored, the known seed survives, nothing is dropped.
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"features\":[{\"name\":\"Policy\",\"refs\":[\"feat-intent\",\"feat-ghost\"]}]}\n```");
        FeatureIndex result = tagger.tag(twoSeedPolicyIndex(), "alice");
        // feat-intent merged (alone, ghost ignored); feat-code untouched → 2 features, all units preserved.
        assertThat(result.features()).hasSize(2);
        assertThat(result.unassignedUnitIds()).isEmpty();
    }

    @Test
    void aRefListedInTwoGroupsLandsInExactlyOneFeature() {
        stubComposer();
        when(llm.isAvailable()).thenReturn(true);
        // feat-intent appears in BOTH groups — it must not be placed in two features.
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"features\":[{\"name\":\"A\",\"refs\":[\"feat-intent\",\"feat-code\"]},"
                        + "{\"name\":\"B\",\"refs\":[\"feat-intent\"]}]}\n```");
        FeatureIndex result = tagger.tag(twoSeedPolicyIndex(), "alice");
        long withJira = result.features().values().stream().filter(f -> f.unitIds().contains("JIRA-1")).count();
        assertThat(withJira).isEqualTo(1);
        assertThat(result.unassignedUnitIds()).isEmpty();
    }

    @Test
    void echoingASingleSeedPreservesItsFeatureId() {
        // featureId stability matters: override carry-forward (Phase 3b persistence) will key on featureId+unitId.
        // Build the seed via the real FeatureSeeder so its ids are the real content hashes, then echo one feature.
        FeatureSeeder seeder = new FeatureSeeder();
        List<EvidenceUnit> units = List.of(
                EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Get policy rule", "t", null, Set.of("policy", "rule")),
                EvidenceUnit.of("CONF#1", SourceKind.CONFLUENCE, UnitType.DESIGN, "Policy design", "t", null, Set.of("policy", "rule")),
                EvidenceUnit.of("JIRA-2", SourceKind.JIRA, UnitType.REQUIREMENT, "Audit log event", "t", null, Set.of("audit", "log")),
                EvidenceUnit.of("CONF#2", SourceKind.CONFLUENCE, UnitType.DESIGN, "Audit design", "t", null, Set.of("audit", "log")));
        FeatureIndex seed = seeder.seed(units, new SourceMix(false, true, true));
        String policyId = seed.features().values().stream()
                .filter(f -> f.unitIds().contains("JIRA-1")).findFirst().orElseThrow().featureId();

        stubComposer();
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"features\":[{\"name\":\"Policy management\",\"refs\":[\"" + policyId + "\"]}]}\n```");

        FeatureIndex result = tagger.tag(seed, "alice");
        // The echoed single seed keeps its content-derived id (only the display name changes); the audit seed survives.
        assertThat(result.features()).containsKey(policyId);
        assertThat(result.features().get(policyId).displayName()).isEqualTo("Policy management");
        assertThat(result.features()).hasSize(2);
    }

    @Test
    void theSchemaFileAcceptsValidRepliesAndRejectsInvalidOnes() throws Exception {
        ResponseSchemaValidator real = new ResponseSchemaValidator(new DefaultResourceLoader());
        ObjectMapper om = new ObjectMapper();
        real.validate(om.readTree("{\"features\":[]}"), "feature-tagger.schema.json");                       // mock identity
        real.validate(om.readTree("{\"features\":[{\"name\":\"X\",\"refs\":[\"a\",\"b\"]}]}"), "feature-tagger.schema.json");
        assertThatThrownBy(() -> real.validate(om.readTree("{\"wrong\":1}"), "feature-tagger.schema.json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
