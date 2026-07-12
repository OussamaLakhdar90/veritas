package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.codegen.BuildVerifier;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ConsumerBuild;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ModuleBuild;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ReactorInputs;
import ca.bnc.qe.veritas.snyk.fix.CascadeVerifier.ReactorResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The local reactor gate: install the framework chain, then compile-check each consumer app against it (a fixed
 * {@code mvn clean install -DskipTests}); the first failure decides. The apps' own tests run in CI/Jenkins after the PR.
 */
class CascadeVerifierTest {

    private final BuildVerifier bv = mock(BuildVerifier.class);
    private final CascadeVerifier verifier = new CascadeVerifier(bv, 900);

    private ReactorInputs inputs() {
        return new ReactorInputs(Path.of("localrepo"),
                List.of(new ModuleBuild("BOM", Path.of("bom")), new ModuleBuild("core", Path.of("core"))),
                List.of(new ConsumerBuild("app7576", Path.of("app"))));
    }

    @Test
    void allCompilePassesTheGate() {
        when(bv.verify(any(), any(), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isTrue();
    }

    @Test
    void everyConsumerIsBuiltWithTheFixedCompileOnlyCommand() {
        // No AI, no per-app command: every consumer is compile-checked with the same fixed skip-tests install so the
        // reactor never tries to RUN the app's tests (those run in Jenkins).
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        when(bv.verify(any(), cmd.capture(), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));

        verifier.verify(new ReactorInputs(Path.of("localrepo"), List.of(),
                List.of(new ConsumerBuild("app7576", Path.of("app")))));

        assertThat(cmd.getAllValues()).anyMatch(c -> c.startsWith(CascadeVerifier.CONSUMER_BUILD_COMMAND));
        assertThat(cmd.getAllValues()).anyMatch(c -> c.contains("clean install") && c.contains("-DskipTests"));
        assertThat(cmd.getAllValues()).noneMatch(c -> c.contains(" test "));   // never runs a bare `test` phase
    }

    @Test
    void aConsumerCompileFailureNamesTheApp() {
        when(bv.verify(any(), contains("-DskipTests install"), anyLong()))
                .thenReturn(new BuildVerifier.BuildResult("PASS", ""));   // framework installs OK
        when(bv.verify(any(), contains("clean install"), anyLong()))
                .thenReturn(new BuildVerifier.BuildResult("FAIL", "cannot find symbol: method removedApi()"));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.failingLabel()).isEqualTo("consumer:app7576");
        assertThat(r.outputTail()).contains("cannot find symbol");
    }

    @Test
    void nothingToBuildFailsTheGateInsteadOfAVacuousPass() {
        // A total clone failure leaves no modules/consumers — must NOT green-light a non-existent fix.
        ReactorResult r = verifier.verify(new ReactorInputs(Path.of("localrepo"), List.of(), List.of()));
        assertThat(r.passed()).isFalse();
        assertThat(r.failingLabel()).isEqualTo("reactor");
        org.mockito.Mockito.verifyNoInteractions(bv);   // never even tried to build
    }

    @Test
    void aFrameworkInstallFailureStopsBeforeCompilingConsumers() {
        when(bv.verify(any(), any(), anyLong())).thenReturn(new BuildVerifier.BuildResult("FAIL", "cannot compile core"));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.failingLabel()).isEqualTo("BOM");
        verify(bv, times(1)).verify(any(), any(), anyLong());   // stopped at the first module — never reached the consumer
    }

    @Test
    void aFailureWithNoBuildOutputStillFailsCleanly() {
        // The build tail is logged (null-output branch → "(no build output captured)"); the gate result is unaffected.
        when(bv.verify(any(), any(), anyLong())).thenReturn(new BuildVerifier.BuildResult("FAIL", null));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.failingLabel()).isEqualTo("BOM");
    }

    @Test
    void aFailureWithBlankBuildOutputStillFailsCleanly() {
        // Blank (whitespace-only) output → the same "no build output captured" log branch.
        when(bv.verify(any(), any(), anyLong())).thenReturn(new BuildVerifier.BuildResult("FAIL", "   "));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
    }

    @Test
    void aConsumerInfrastructureFailureIsInconclusiveNotBreaking() {
        // A dependency that can't be fetched is the build ENVIRONMENT, not the upgrade — classify INCONCLUSIVE (review
        // manually), never a false "breaking change".
        when(bv.verify(any(), contains("-DskipTests install"), anyLong()))
                .thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains("clean install"), anyLong())).thenReturn(new BuildVerifier.BuildResult(
                "FAIL", "Could not resolve dependencies for project: ... connection timed out"));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.inconclusive()).isTrue();
        assertThat(r.failingLabel()).isEqualTo("consumer:app7576");
    }

    @Test
    void aGenuineConsumerCompileFailureIsBreakingNotInconclusive() {
        // A real compile break against the upgraded framework (no infra signature) stays a genuine break — the gate
        // must not be too lenient.
        when(bv.verify(any(), contains("-DskipTests install"), anyLong()))
                .thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains("clean install"), anyLong())).thenReturn(new BuildVerifier.BuildResult(
                "FAIL", "BUILD FAILURE — incompatible types: String cannot be converted to UUID"));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.inconclusive()).isFalse();
        assertThat(r.failingLabel()).isEqualTo("consumer:app7576");
    }

    @Test
    void aLongBuildOutputIsTailedInTheLogButReturnedInFull() {
        // A >2 KB output exercises the truncation branch of the log tail; the returned outputTail keeps the full text.
        String huge = "x".repeat(5000);
        when(bv.verify(any(), contains("-DskipTests install"), anyLong()))
                .thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains("clean install"), anyLong())).thenReturn(new BuildVerifier.BuildResult("FAIL", huge));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.outputTail()).hasSize(5000);   // the train still gets the whole log; only the console line is tailed
    }
}
