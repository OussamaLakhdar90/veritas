package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the real Copilot CLI gateway without needing the {@code copilot} binary: the available-probe
 * is verified against the JVM's own {@code java --version} (guaranteed present, exits 0), and the failure paths
 * against a binary that does not exist. No process happy-path test — that needs a real/fake copilot binary.
 */
class CopilotCliClientTest {

    private CopilotCliClient client(String binary) {
        CopilotCliClient c = new CopilotCliClient();
        ReflectionTestUtils.setField(c, "binary", binary);
        ReflectionTestUtils.setField(c, "timeoutSeconds", 15L);
        return c;
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    @Test
    void isAvailableTrueWhenBinaryRespondsToVersion() {
        assertThat(client(javaExecutable()).isAvailable()).isTrue();   // `java --version` exits 0
    }

    @Test
    void isAvailableFalseWhenBinaryMissing() {
        assertThat(client("veritas-no-such-binary-xyz").isAvailable()).isFalse();   // catches the spawn failure
    }

    @Test
    void completeThrowsClearlyWhenBinaryMissing() {
        assertThatThrownBy(() -> client("veritas-no-such-binary-xyz").complete("hello", "claude-sonnet-4.6"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copilot CLI invocation failed");
    }
}
