package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.SourceMix;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Evidence-first section generation: grounded → returned; ungrounded → regenerate once → drop; never abort. */
class EvidenceFirstSectionGeneratorTest {

    private final LlmGateway llm = mock(LlmGateway.class);
    private final ModelSelector modelSelector = mock(ModelSelector.class);
    private final CostRecorder costRecorder = mock(CostRecorder.class);
    private final PromptComposer promptComposer = mock(PromptComposer.class);
    private final ResponseSchemaValidator schemaValidator = mock(ResponseSchemaValidator.class);

    private final EvidenceFirstSectionGenerator gen = new EvidenceFirstSectionGenerator(llm, modelSelector,
            costRecorder, promptComposer, new JsonBlockExtractor(), schemaValidator, new ObjectMapper(),
            new EvidenceRetriever(), new CitationValidator());

    private final FeatureIndex index = new FeatureIndex(
            Map.of("feat-1", new Feature("feat-1", "login", List.of("JIRA-1"), FeatureStatus.PLANNED)),
            Map.of("JIRA-1", EvidenceUnit.of("JIRA-1", SourceKind.JIRA, UnitType.REQUIREMENT, "Lockout",
                    "Account locks after 5 attempts", null, Set.of())),
            Set.of(), Set.of(), new SourceMix(false, true, false), "src");

    private static final String GOOD =
            "```json\n{\"feature\":\"login\",\"evidence\":[{\"unitId\":\"JIRA-1\",\"quote\":\"locks after 5\"}],\"content\":\"x\"}\n```";
    private static final String BAD =
            "```json\n{\"feature\":\"login\",\"evidence\":[{\"unitId\":\"GHOST-9\"}],\"content\":\"x\"}\n```";

    private void stub() {
        when(llm.isAvailable()).thenReturn(true);
        when(promptComposer.data(anyString(), anyString())).thenReturn("DATA");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("PROMPT");
        when(modelSelector.resolveTier(any())).thenReturn("mock-model");
    }

    private Optional<JsonNode> run() {
        return gen.generate("riskRegister", "List the risks.", Set.of(), ModelTier.DEEP, index, "feat-1", "alice");
    }

    @Test
    void returnsAGroundedSectionWithoutRetry() {
        stub();
        when(llm.complete(anyString(), anyString())).thenReturn(GOOD);
        Optional<JsonNode> r = run();
        assertThat(r).isPresent();
        assertThat(r.get().path("feature").asText()).isEqualTo("login");
        verify(llm, times(1)).complete(anyString(), anyString());   // grounded first try → no regenerate
    }

    @Test
    void regeneratesOnceWhenTheFirstReplyIsUngroundedThenSucceeds() {
        stub();
        when(llm.complete(anyString(), anyString())).thenReturn(BAD, GOOD);
        Optional<JsonNode> r = run();
        assertThat(r).isPresent();
        verify(llm, times(2)).complete(anyString(), anyString());   // one regenerate with the feedback
    }

    @Test
    void dropsTheSectionWhenStillUngroundedAfterTheRetry() {
        stub();
        when(llm.complete(anyString(), anyString())).thenReturn(BAD, BAD);
        Optional<JsonNode> r = run();
        assertThat(r).isEmpty();                                     // dropped, not aborted
        verify(llm, times(2)).complete(anyString(), anyString());
    }

    @Test
    void returnsEmptyWithoutSpendWhenLlmUnavailable() {
        when(llm.isAvailable()).thenReturn(false);
        assertThat(run()).isEmpty();
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void theSectionSchemaRequiresAtLeastOneEvidenceEntry() throws Exception {
        ResponseSchemaValidator real = new ResponseSchemaValidator(new DefaultResourceLoader());
        ObjectMapper om = new ObjectMapper();
        real.validate(om.readTree("{\"feature\":\"x\",\"evidence\":[{\"unitId\":\"a\"}],\"content\":{}}"),
                "test-strategy-section.schema.json");   // valid
        assertThatThrownBy(() -> real.validate(om.readTree("{\"feature\":\"x\",\"evidence\":[],\"content\":{}}"),
                "test-strategy-section.schema.json")).isInstanceOf(IllegalStateException.class);   // minItems 1
        assertThatThrownBy(() -> real.validate(om.readTree("{\"feature\":\"x\",\"content\":{}}"),
                "test-strategy-section.schema.json")).isInstanceOf(IllegalStateException.class);   // evidence required
    }
}
