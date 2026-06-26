package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.codegen.BuildVerifier.BuildResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Branch-coverage tests for {@link BuildVerifier} using the JVM's own {@code java} launcher as a
 * guaranteed-present external process: {@code java --version} exits 0 (PASS), {@code java --help}
 * emits a long banner (exercises {@link BuildVerifier} output-tailing on a PASS), and an unknown
 * flag exits non-zero (FAIL). The null/blank, spawn-failure and command-splitting branches are
 * covered without depending on any OS-specific shell.
 */
class BuildVerifierBranchTest {

    private final BuildVerifier verifier = new BuildVerifier();

    /** Absolute path to this JVM's own launcher — present on every box the suite runs on. */
    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    @Test
    void nullCommandIsSkippedWithEmptyOutput(@TempDir Path dir) {
        BuildResult r = verifier.verify(dir, null);
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.output()).isEmpty();
    }

    @Test
    void emptyCommandIsSkippedWithEmptyOutput(@TempDir Path dir) {
        BuildResult r = verifier.verify(dir, "");
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.output()).isEmpty();
    }

    @Test
    void whitespaceOnlyCommandIsSkipped(@TempDir Path dir) {
        // isBlank() short-circuits before any spawn attempt.
        BuildResult r = verifier.verify(dir, "   \t  ");
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.output()).isEmpty();
    }

    @Test
    void exitZeroIsPassAndCapturesOutput(@TempDir Path dir) {
        // `java --version` exits 0 and prints a short banner to stdout.
        BuildResult r = verifier.verify(dir, javaExecutable() + " --version");
        assertThat(r.status()).isEqualTo("PASS");
        // The banner names the runtime; assert real captured content (works on Oracle "Java(TM)..." AND the CI's
        // OpenJDK/Temurin "OpenJDK Runtime Environment" — both contain "runtime environment", not both "java").
        assertThat(r.output().toLowerCase()).contains("runtime environment");
    }

    @Test
    void leadingAndCollapsedWhitespaceInCommandIsTrimmedAndSplit(@TempDir Path dir) {
        // Surrounding + multiple inner spaces must still resolve to `<java> --version` (trim + \\s+ split).
        String padded = "   " + javaExecutable() + "    --version   ";
        BuildResult r = verifier.verify(dir, padded);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.output().toLowerCase()).contains("runtime environment");
    }

    @Test
    void longOutputOnPassIsTailedToFourThousandChars(@TempDir Path dir) {
        // `java --help` prints a banner well over 4000 chars; tail() must clamp the captured output.
        BuildResult r = verifier.verify(dir, javaExecutable() + " --help");
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.output()).isNotEmpty();
        assertThat(r.output().length()).isLessThanOrEqualTo(4000);
    }

    @Test
    void nonZeroExitIsFailAndCapturesErrorOutput(@TempDir Path dir) {
        // An unknown launcher flag exits non-zero; redirectErrorStream means the message is captured.
        BuildResult r = verifier.verify(dir, javaExecutable() + " --veritas-bogus-flag-xyz");
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.output()).isNotEmpty();
    }

    @Test
    void unrunnableCommandIsFailWithExceptionMessage(@TempDir Path dir) {
        // Spawn failure → the catch branch returns FAIL carrying the exception message. Use an ALLOW-LISTED tool
        // (so it isn't short-circuited to SKIPPED) but a non-existent working dir so ProcessBuilder.start() throws.
        BuildResult r = verifier.verify(dir.resolve("does-not-exist"), javaExecutable() + " --version");
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.output()).isNotEmpty();
    }

    @Test
    void nonAllowListedExecutableIsSkippedNotRun(@TempDir Path dir) {
        // A verifyCommand from a (semi-trusted) template whose program isn't an allow-listed build tool must NOT run.
        BuildResult r = verifier.verify(dir, "veritas-no-such-command-xyz123 --flag");
        assertThat(r.status()).isEqualTo("SKIPPED");
        assertThat(r.output()).contains("not an allow-listed");
    }

    @Test
    void runsInTheSuppliedWorkingDirectory(@TempDir Path dir) {
        // Proves the working directory is honored: print user.dir from the temp dir and read it back.
        BuildResult r = verifier.verify(dir,
                javaExecutable() + " -XshowSettings:properties -version");
        assertThat(r.status()).isEqualTo("PASS");
        // The captured settings banner reports user.dir; it must reflect our @TempDir, not the suite cwd.
        String dirName = dir.getFileName().toString();
        assertThat(r.output()).contains(dirName);
    }

    @Test
    void passAndFailAreDistinctStatusesForSameVerifier(@TempDir Path dir) {
        // Same instance classifies independently across calls (no shared state leaks between runs).
        String pass = verifier.verify(dir, javaExecutable() + " --version").status();
        String fail = verifier.verify(dir, javaExecutable() + " --veritas-bogus-flag-xyz").status();
        assertThat(pass).isEqualTo("PASS");
        assertThat(fail).isEqualTo("FAIL");
    }

    @Test
    void buildResultRecordExposesStatusAndOutput() {
        BuildResult r = new BuildResult("PASS", "all good");
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.output()).isEqualTo("all good");
        assertThat(r).isEqualTo(new BuildResult("PASS", "all good"));
        assertThat(r).isNotEqualTo(new BuildResult("FAIL", "all good"));
    }
}
