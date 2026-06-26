package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Branch/edge coverage for {@link CopilotCliClient} that the original {@code CopilotCliClientTest} leaves
 * untouched: the {@code complete()} retry loop, the happy path (exit 0 with stdout), the non-zero-exit path
 * (stderr surfaced in the thrown message), the empty-output path (retries then throws), the timeout path, and
 * the {@code maxAttempts}/{@code --model} branches.
 *
 * <p>Cross-platform paths drive the JVM's own launcher ({@code java}); the process happy/error/timeout paths
 * need a binary that <em>ignores</em> the fixed {@code copilot} flags, so those tests are Windows-only and use
 * a throwaway {@code .cmd} stub which {@code ProcessBuilder} can execute directly.
 */
class CopilotCliClientCoverageTest {

    private CopilotCliClient client(String binary) {
        return client(binary, 15L, 2);
    }

    private CopilotCliClient client(String binary, long timeoutSeconds, int maxAttempts) {
        CopilotCliClient c = new CopilotCliClient();
        ReflectionTestUtils.setField(c, "binary", binary);
        ReflectionTestUtils.setField(c, "timeoutSeconds", timeoutSeconds);
        ReflectionTestUtils.setField(c, "maxAttempts", maxAttempts);
        return c;
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    /** Writes a {@code .cmd} stub that ignores its args, prints {@code body} lines and exits {@code code}. */
    private static Path cmdStub(Path dir, String name, int exitCode, String stdout, String stderr)
            throws Exception {
        StringBuilder sb = new StringBuilder("@echo off\r\n");
        if (stdout != null && !stdout.isEmpty()) {
            sb.append("echo ").append(stdout).append("\r\n");
        }
        if (stderr != null && !stderr.isEmpty()) {
            sb.append("echo ").append(stderr).append(" 1>&2\r\n");
        }
        sb.append("exit /b ").append(exitCode).append("\r\n");
        Path script = dir.resolve(name + ".cmd");
        Files.writeString(script, sb.toString());
        return script;
    }

    // ----- isAvailable() branches -------------------------------------------------------------

    @Test
    void isAvailableTrueWhenVersionExitsZero() {
        assertThat(client(javaExecutable()).isAvailable()).isTrue();
    }

    @Test
    void isAvailableFalseWhenSpawnFails() {
        // No such binary -> ProcessBuilder.start() throws -> caught -> false.
        assertThat(client("veritas-no-such-binary-xyz").isAvailable()).isFalse();
    }

    @Test
    @EnabledOnOs(WINDOWS)
    void isAvailableFalseWhenVersionExitsNonZero(@TempDir Path dir) throws Exception {
        // Binary spawns fine but `--version` returns 3 -> exitValue() != 0 -> false.
        Path stub = cmdStub(dir, "ver-bad", 3, "irrelevant", null);
        assertThat(client(stub.toString()).isAvailable()).isFalse();
    }

    @Test
    @EnabledOnOs(WINDOWS)
    void isAvailableTrueWhenVersionExitsZeroViaStub(@TempDir Path dir) throws Exception {
        Path stub = cmdStub(dir, "ver-ok", 0, "copilot 1.2.3", null);
        assertThat(client(stub.toString()).isAvailable()).isTrue();
    }

    // ----- complete() error paths (cross-platform) --------------------------------------------

    @Test
    void completeThrowsWhenBinaryMissing() {
        // start() throws -> wrapped as "invocation failed"; retried then rethrown.
        assertThatThrownBy(() -> client("veritas-no-such-binary-xyz").complete("hello", "claude-sonnet-4.6"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copilot CLI invocation failed");
    }

    @Test
    void completeThrowsOnNonZeroExitFromRealLauncher() {
        // `java -p hello -s ...` is an unrecognized-option error -> exit 1 -> "exited 1".
        assertThatThrownBy(() -> client(javaExecutable()).complete("hello", "some-model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copilot CLI exited");
    }

    // ----- complete() happy path + model branch (Windows stub) --------------------------------

    @Test
    @EnabledOnOs(WINDOWS)
    void completeReturnsStdoutOnSuccess(@TempDir Path dir) throws Exception {
        Path stub = cmdStub(dir, "ok", 0, "HELLO_FROM_FAKE", null);
        String out = client(stub.toString()).complete("prompt-text", "claude-sonnet-4.6");
        assertThat(out).contains("HELLO_FROM_FAKE");
    }

    @Test
    @EnabledOnOs(WINDOWS)
    void completeSucceedsWithNullModel(@TempDir Path dir) throws Exception {
        // model == null -> the `--model` arg pair is skipped; stub ignores args either way.
        Path stub = cmdStub(dir, "ok-nullmodel", 0, "NO_MODEL_OK", null);
        assertThat(client(stub.toString()).complete("p", null)).contains("NO_MODEL_OK");
    }

    @Test
    @EnabledOnOs(WINDOWS)
    void completeSucceedsWithBlankModel(@TempDir Path dir) throws Exception {
        // model is blank -> `--model` skipped (other half of the model guard).
        Path stub = cmdStub(dir, "ok-blankmodel", 0, "BLANK_MODEL_OK", null);
        assertThat(client(stub.toString()).complete("p", "   ")).contains("BLANK_MODEL_OK");
    }

    // ----- complete() non-zero exit surfaces stderr -------------------------------------------

    @Test
    @EnabledOnOs(WINDOWS)
    void completeThrowsWithStderrOnNonZeroExit(@TempDir Path dir) throws Exception {
        Path stub = cmdStub(dir, "boom", 7, null, "BOOM_DETAIL");
        assertThatThrownBy(() -> client(stub.toString()).complete("p", "m"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copilot CLI exited 7")
                .hasMessageContaining("BOOM_DETAIL");
    }

    // ----- complete() empty-output path -------------------------------------------------------

    @Test
    @EnabledOnOs(WINDOWS)
    void completeThrowsEmptyOutputAfterRetriesWhenStdoutBlank(@TempDir Path dir) throws Exception {
        // exit 0 but nothing on stdout -> out.isBlank() -> "returned empty output" after both attempts.
        Path stub = cmdStub(dir, "empty", 0, null, null);
        assertThatThrownBy(() -> client(stub.toString(), 15L, 2).complete("p", "m"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copilot CLI returned empty output");
    }

    // ----- complete() timeout path ------------------------------------------------------------

    @Test
    @EnabledOnOs(WINDOWS)
    void completeThrowsOnTimeout(@TempDir Path dir) throws Exception {
        // Stub sleeps ~3s via ping; timeout is 1s -> destroyForcibly -> "timed out after 1s".
        Path slow = dir.resolve("slow.cmd");
        Files.writeString(slow, "@echo off\r\nping -n 4 127.0.0.1 >nul\r\necho LATE\r\n");
        assertThatThrownBy(() -> client(slow.toString(), 1L, 1).complete("p", "m"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copilot CLI timed out after 1s");
    }

    // ----- maxAttempts branches ---------------------------------------------------------------

    @Test
    void completeWithZeroMaxAttemptsIsCoercedToSingleAttempt() {
        // Math.max(1, 0) == 1 -> exactly one attempt, no retry-log branch, then rethrow.
        assertThatThrownBy(() -> client("veritas-no-such-binary-xyz", 15L, 0).complete("p", "m"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copilot CLI invocation failed");
    }

    @Test
    @EnabledOnOs(WINDOWS)
    void completeSucceedsOnFirstAttemptWithoutRetry(@TempDir Path dir) throws Exception {
        // maxAttempts == 1 and stub succeeds immediately -> loop returns inside the first iteration.
        Path stub = cmdStub(dir, "ok-single", 0, "SINGLE_OK", null);
        assertThat(client(stub.toString(), 15L, 1).complete("p", "m")).contains("SINGLE_OK");
    }
}
