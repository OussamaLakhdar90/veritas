package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildVerifierTest {

    private final BuildVerifier verifier = new BuildVerifier();

    @Test
    void skipsWhenNoCommand() {
        assertThat(verifier.verify(Path.of("."), "").status()).isEqualTo("SKIPPED");
        assertThat(verifier.verify(Path.of("."), null).status()).isEqualTo("SKIPPED");
    }

    @Test
    void skipsACommandWhoseExecutableIsNotAllowListed() {
        // The verifyCommand comes from a semi-trusted template, so a non-build-tool program is refused (not run).
        assertThat(verifier.verify(Path.of("."), "veritas-no-such-command-xyz123").status()).isEqualTo("SKIPPED");
    }

    @Test
    void resolveProgramLeavesTheToolUnchangedOnNonWindows() {
        assertThat(BuildVerifier.resolveProgram("mvn", Path.of("."), "Linux", Map.of())).isEqualTo("mvn");
        assertThat(BuildVerifier.resolveProgram("gradle", Path.of("."), "Mac OS X", Map.of())).isEqualTo("gradle");
    }

    @Test
    void resolveProgramAddsTheWindowsScriptExtensionSoProcessBuilderCanFindIt() {
        // The bug: `mvn` on Windows is `mvn.cmd`; a bare "mvn" dies with CreateProcess error=2 (no PATHEXT).
        assertThat(BuildVerifier.resolveProgram("mvn", Path.of("."), "Windows 11", Map.of())).isEqualTo("mvn.cmd");
        assertThat(BuildVerifier.resolveProgram("gradle", Path.of("."), "Windows 11", Map.of())).isEqualTo("gradle.bat");
        assertThat(BuildVerifier.resolveProgram("npm", Path.of("."), "Windows 11", Map.of())).isEqualTo("npm.cmd");
        // .exe tools (java/node/python/go/dotnet/cargo/make) resolve on PATH as-is — no remap.
        assertThat(BuildVerifier.resolveProgram("java", Path.of("."), "Windows 11", Map.of())).isEqualTo("java");
    }

    @Test
    void resolveProgramPrefersAnAbsolutePathFromM2HomeOnWindows(@TempDir Path home) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path mvnCmd = Files.writeString(bin.resolve("mvn.cmd"), "@echo off");
        String resolved = BuildVerifier.resolveProgram("mvn", Path.of("."), "Windows 11",
                Map.of("M2_HOME", home.toString()));
        assertThat(resolved).isEqualTo(mvnCmd.toString());   // absolute → works even when mvn isn't on the app PATH
    }

    @Test
    void resolveProgramPrefersAProjectWrapperInTheWorkingDirOnWindows(@TempDir Path project) throws Exception {
        Path wrapper = Files.writeString(project.resolve("mvnw.cmd"), "@echo off");
        assertThat(BuildVerifier.resolveProgram("mvnw", project, "Windows 11", Map.of())).isEqualTo(wrapper.toString());
        // …and falls back to the bare script name when no wrapper is present.
        assertThat(BuildVerifier.resolveProgram("mvnw", project.resolve("missing"), "Windows 11", Map.of()))
                .isEqualTo("mvnw.cmd");
    }
}
