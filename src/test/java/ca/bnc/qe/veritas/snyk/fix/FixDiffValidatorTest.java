package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** The advisory AI cross-check that explains what a fix changed — degrade-safe, never a gate. */
class FixDiffValidatorTest {

    private final LlmGateway llm = mock(LlmGateway.class);
    private final PromptComposer composer = mock(PromptComposer.class);
    private final ModelSelector modelSelector = mock(ModelSelector.class);
    private final CostRecorder costRecorder = mock(CostRecorder.class);

    private final FixDiffValidator validator = new FixDiffValidator(llm, composer, modelSelector, costRecorder,
            new JsonBlockExtractor(), new ResponseSchemaValidator(new DefaultResourceLoader()), new ObjectMapper());

    @BeforeEach
    void stub() {
        when(llm.isAvailable()).thenReturn(true);
        when(composer.data(anyString(), anyString())).thenReturn("block");
        when(composer.compose(anyString(), anyString(), any(), anyString(), anyString(), anyInt())).thenReturn("prompt");
        when(modelSelector.resolveTier(any())).thenReturn("claude-sonnet-4.6");
        when(modelSelector.promptTokenCap(any())).thenReturn(60000);
    }

    @Test
    void explainsAConfirmedFix() {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"fixesTheVuln\":true,\"whatChanged\":\"Raised jackson-databind 3.1.1 -> 3.1.4 in "
                        + "dependencyManagement\",\"reason\":\"Reaches the safe version 3.1.4.\"}\n```");

        FixDiffVerdict v = validator.explain("tools.jackson.core:jackson-databind", "3.1.1", "3.1.4",
                "<old/>", "<new/>", "alice", "t1");

        assertThat(v.available()).isTrue();
        assertThat(v.fixesTheVuln()).isTrue();
        assertThat(v.whatChanged()).contains("3.1.4");
    }

    @Test
    void flagsAReleaseOnlyDiffAsNotFixing() {
        when(llm.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"fixesTheVuln\":false,\"whatChanged\":\"Only the BOM's own release version moved\","
                        + "\"reason\":\"jackson-databind was unchanged.\"}\n```");

        FixDiffVerdict v = validator.explain("tools.jackson.core:jackson-databind", "3.1.1", "3.1.4",
                "<old/>", "<new/>", "alice", "t1");

        assertThat(v.available()).isTrue();
        assertThat(v.fixesTheVuln()).isFalse();
    }

    @Test
    void degradesToUnavailableWhenCopilotOfflineOrTheJudgeFails() {
        when(llm.isAvailable()).thenReturn(false);
        assertThat(validator.explain("a:b", "1", "2", "<o/>", "<n/>", "alice", "t1").available()).isFalse();

        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("not json");   // a judge failure never blocks
        FixDiffVerdict v = validator.explain("a:b", "1", "2", "<o/>", "<n/>", "alice", "t1");
        assertThat(v.available()).isFalse();
        assertThat(v.reason()).containsIgnoringCase("unavailable");
    }
}
