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

/** The advisory breaking-change judge — parses a structured verdict, and degrades safely when Copilot is off. */
class BreakingChangeServiceTest {

    private final LlmGateway llm = mock(LlmGateway.class);
    private final PromptComposer composer = mock(PromptComposer.class);
    private final ModelSelector modelSelector = mock(ModelSelector.class);
    private final CostRecorder costRecorder = mock(CostRecorder.class);
    private final BreakingChangeService service = new BreakingChangeService(llm, composer, modelSelector,
            costRecorder, new JsonBlockExtractor(),
            new ResponseSchemaValidator(new DefaultResourceLoader()), new ObjectMapper());

    @BeforeEach
    void stub() {
        when(composer.data(anyString(), anyString())).thenReturn("data\n");
        when(composer.compose(anyString(), anyString(), any(), anyString(), anyString(), anyInt())).thenReturn("prompt");
        when(modelSelector.resolveTier(any())).thenReturn("claude-sonnet");
        when(modelSelector.promptTokenCap(any())).thenReturn(60000);
    }

    @Test
    void parsesABreakingVerdict() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(
                "prose\n```json\n{\"breaking\":true,\"confidence\":85,\"reasons\":[\"major version bump\"],"
                        + "\"migrationNotes\":\"adapt the changed API\"}\n```");

        BreakingVerdict v = service.judge("com.x:y", "2.0.0", "3.0.0", "import com.x.y.Foo;", "alice", "train-1");

        assertThat(v.available()).isTrue();
        assertThat(v.breaking()).isTrue();
        assertThat(v.confidence()).isEqualTo(85);
        assertThat(v.reasons()).contains("major version bump");
        assertThat(v.migrationNotes()).isEqualTo("adapt the changed API");
    }

    @Test
    void degradesToUnavailableWhenCopilotIsOff() {
        when(llm.isAvailable()).thenReturn(false);
        BreakingVerdict v = service.judge("com.x:y", "2.0.0", "2.0.1", "", "alice", "train-1");
        assertThat(v.available()).isFalse();
        assertThat(v.breaking()).isFalse();
    }

    @Test
    void degradesToUnavailableWhenTheReplyIsMalformed() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("no json here");
        BreakingVerdict v = service.judge("com.x:y", "2.0.0", "3.0.0", "", "alice", "train-1");
        assertThat(v.available()).isFalse();   // never blocks the fix — the reactor build decides
    }

    @Test
    void detectsAMajorVersionBump() {
        assertThat(BreakingChangeService.isMajorBump("2.14.0", "3.0.0")).isTrue();
        assertThat(BreakingChangeService.isMajorBump("3.1.1", "3.2.0")).isFalse();
        assertThat(BreakingChangeService.isMajorBump("1.0", "abc")).isFalse();
    }
}
