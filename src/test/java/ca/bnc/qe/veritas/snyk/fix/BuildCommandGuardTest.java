package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The security allow-list for the AI-derived reactor command — the difference between "run the app's tests" and a
 *  command-execution / path-escape sink. */
class BuildCommandGuardTest {

    private final Path repo = Path.of("build", "repo").toAbsolutePath();

    @Test
    void acceptsAPlainTestCommand() {
        assertThat(BuildCommandGuard.sanitize("mvn -q -B test", repo)).isEqualTo("mvn -q -B test");
    }

    @Test
    void acceptsAProfileAndAnInRepoSuiteFile() {
        String cmd = "mvn -q -B -Psystem-test -DsuiteXmlFile=src/test/resources/testng.xml verify";
        assertThat(BuildCommandGuard.sanitize(cmd, repo)).isEqualTo(cmd);
    }

    @Test
    void acceptsSkipTestsAsACompileOnlyEscapeHatch() {
        // -DskipTests still COMPILES the tests (catching API breaks); it is the sanctioned compile-only hatch.
        assertThatCode(() -> BuildCommandGuard.sanitize("mvn -q -B -DskipTests install", repo))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMavenTestSkipWhichAlsoSkipsTestCompilation() {
        // maven.test.skip skips test COMPILATION too — it would hide the API breaks the reactor exists to catch.
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -q -B -Dmaven.test.skip=true test", repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void rejectsSkipItsAndFailIfNoTestsWhichWouldLetZeroTestsPass() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -q -B -DskipITs verify", repo))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -q -B -DfailIfNoTests=false test", repo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAPluginGoal() {
        // exec:exec is the exec-maven-plugin arbitrary-execution vector — it has no allow-list entry.
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn exec:exec -Dexec.executable=cmd verify", repo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTheReservedLocalRepoProperty() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -Dmaven.repo.local=/tmp/evil test", repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void rejectsAnExecProperty() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -Dexec.executable=/bin/sh test", repo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsARelativePathThatEscapesTheRepo() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -DsuiteXmlFile=../../etc/passwd test", repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the repo");
    }

    @Test
    void rejectsAnAbsolutePathOutsideTheRepo() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -DsuiteXmlFile=/etc/passwd test", repo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonMavenProgram() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("gradle test", repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an allow-listed build tool");
    }

    @Test
    void rejectsACommandThatRunsNoTests() {
        // clean is allowed, but a command with no test-running phase is a silent no-op that must not pass as verified.
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -q -B clean", repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs no tests");
    }

    @Test
    void rejectsAnUnknownFlag() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn --spawn-a-shell test", repo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAControlCharacterInAToken() {
        // A bell char (0x07) is not whitespace, so it stays inside one token — defense in depth against smuggled bytes.
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("mvn -Dtest=ab test", repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control character");
    }

    @Test
    void rejectsAnEmptyCommand() {
        assertThatThrownBy(() -> BuildCommandGuard.sanitize("   ", repo))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
