package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ClassificationAdvisorTest {

    private final LlmGateway llm = mock(LlmGateway.class);
    private final PromptComposer composer = mock(PromptComposer.class);
    private final ModelSelector modelSelector = mock(ModelSelector.class);
    private final CostRecorder costRecorder = mock(CostRecorder.class);
    // Real extractor + validator so the actual classification-proposal.schema.json is exercised.
    private final ClassificationAdvisor advisor = new ClassificationAdvisor(llm, composer, modelSelector,
            costRecorder, new JsonBlockExtractor(),
            new ResponseSchemaValidator(new DefaultResourceLoader()), new ObjectMapper());

    @BeforeEach
    void stub() {
        when(llm.isAvailable()).thenReturn(true);
        when(composer.data(anyString(), anyString())).thenReturn("block");
        when(composer.compose(anyString(), anyString(), any(), anyString(), anyString(), anyInt())).thenReturn("prompt");
        when(modelSelector.resolveTier(any())).thenReturn("claude-sonnet");
        when(modelSelector.promptTokenCap(any())).thenReturn(60000);
    }

    @Test
    void suggestsARubricSeverityWithRationale() {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"suggestedSeverity\":\"MAJOR\",\"rationale\":\"Response-shape risk a running consumer depends on.\"}\n```");
        ClassificationAdvisor.Suggestion s = advisor.suggest(FindingType.SCHEMA_FIELD_MISSING,
                Map.of(Severity.MAJOR, 5), 3, "alice");
        assertThat(s.available()).isTrue();
        assertThat(s.severity()).isEqualTo(Severity.MAJOR);
        assertThat(s.rationale()).contains("consumer");
    }

    @Test
    void degradesToUnavailableWhenCopilotOffline() {
        when(llm.isAvailable()).thenReturn(false);
        ClassificationAdvisor.Suggestion s = advisor.suggest(FindingType.SCHEMA_FIELD_MISSING,
                Map.of(Severity.MAJOR, 5), 3, "alice");
        assertThat(s.available()).isFalse();
        assertThat(s.severity()).isNull();
    }

    @Test
    void degradesWhenTheReplyIsMalformed() {
        when(llm.complete(anyString(), anyString())).thenReturn("not json at all");
        assertThat(advisor.suggest(FindingType.SCHEMA_FIELD_MISSING, Map.of(Severity.MAJOR, 5), 3, "alice").available())
                .isFalse();
    }

    @Test
    void degradesWhenTheReplyViolatesTheSchema() {
        // UNSPECIFIED is not an allowed suggestion (the schema enum is the 5 real severities).
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"suggestedSeverity\":\"UNSPECIFIED\",\"rationale\":\"x\"}\n```");
        assertThat(advisor.suggest(FindingType.SCHEMA_FIELD_MISSING, Map.of(Severity.MAJOR, 5), 3, "alice").available())
                .isFalse();
    }
}
