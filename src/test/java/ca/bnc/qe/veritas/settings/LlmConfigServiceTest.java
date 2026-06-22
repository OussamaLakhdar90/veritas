package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class LlmConfigServiceTest {

    private LlmConfigService service(Path dir) {
        LlmConfigService s = new LlmConfigService(new ObjectMapper());
        ReflectionTestUtils.setField(s, "llmFile", dir.resolve("llm.json"));
        return s;
    }

    @Test
    void persistsDesiredModeAndFlagsRestartWhenItDiffersFromActive(@TempDir Path dir) {
        LlmConfigService s = service(dir);
        assertThat(s.desiredMode()).isNull();

        assertThat(s.save("http", "mock")).isTrue();    // differs from running mode → restart needed
        assertThat(s.desiredMode()).isEqualTo("http");

        assertThat(s.save("mock", "mock")).isFalse();   // same as active → applies without restart
    }

    @Test
    void rejectsUnknownMode(@TempDir Path dir) {
        assertThatThrownBy(() -> service(dir).save("bogus", "mock"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LLM mode");
    }
}
