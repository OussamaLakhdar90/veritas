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

/** The local reactor gate: install the framework chain, then test each app; the first failure decides. */
class CascadeVerifierTest {

    private final BuildVerifier bv = mock(BuildVerifier.class);
    private final CascadeVerifier verifier = new CascadeVerifier(bv, 900);

    private ReactorInputs inputs() {
        return new ReactorInputs(Path.of("localrepo"),
                List.of(new ModuleBuild("BOM", Path.of("bom")), new ModuleBuild("core", Path.of("core"))),
                List.of(new ConsumerBuild("app7576", Path.of("app"))));
    }

    @Test
    void allPassOpensTheTrain() {
        when(bv.verify(any(), any(), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isTrue();
    }

    @Test
    void aConsumerTestFailureNamesTheApp() {
        when(bv.verify(any(), contains("install"), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains(" test "), anyLong())).thenReturn(new BuildVerifier.BuildResult("FAIL", "boom in app"));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.failingLabel()).isEqualTo("consumer:app7576");
        assertThat(r.outputTail()).contains("boom in app");
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
    void aFrameworkInstallFailureStopsBeforeTestingConsumers() {
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
    void aConsumerConfigErrorIsInconclusiveNotBreaking() {
        // The exact ciam-oauth case: a TestNG suite-file config error is the app's own build setup, not the upgrade.
        // It must be classified INCONCLUSIVE (review manually), never a false "breaking change".
        when(bv.verify(any(), contains("install"), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains(" test "), anyLong())).thenReturn(new BuildVerifier.BuildResult(
                "FAIL", "maven-surefire-plugin:test failed: The parameters 'testSuiteXmlFiles0' has null value"));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.inconclusive()).isTrue();
        assertThat(r.failingLabel()).isEqualTo("consumer:app7576");
    }

    @Test
    void aGenuineConsumerTestFailureIsBreakingNotInconclusive() {
        // A real assertion failure (no config/infra signature) stays a genuine break — the gate must not be too lenient.
        when(bv.verify(any(), contains("install"), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains(" test "), anyLong())).thenReturn(new BuildVerifier.BuildResult(
                "FAIL", "Tests run: 12, Failures: 1 — expected <200> but was <500>"));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.inconclusive()).isFalse();
        assertThat(r.failingLabel()).isEqualTo("consumer:app7576");
    }

    @Test
    void aCompileOnlySkipTestsPassIsInconclusiveNotClean() {
        // A command that skips test EXECUTION compiled the app but never ran its tests — never present that as a
        // verified clean pass (that would auto-open a PR train for an un-tested upgrade).
        when(bv.verify(any(), contains("install"), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains("-DskipTests"), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        ReactorResult r = verifier.verify(new ReactorInputs(Path.of("localrepo"), List.of(),
                List.of(new ConsumerBuild("app7576", Path.of("app"), "mvn -q -B -DskipTests test"))));
        assertThat(r.passed()).isFalse();
        assertThat(r.inconclusive()).isTrue();
        assertThat(r.failingLabel()).isEqualTo("consumer:app7576");
    }

    @Test
    void runsTheAppsOwnStoredCommandWhenOneIsProvided() {
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        when(bv.verify(any(), cmd.capture(), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        ReactorInputs in = new ReactorInputs(Path.of("localrepo"), List.of(),
                List.of(new ConsumerBuild("app7576", Path.of("app"), "mvn -q -B -Psystem-test verify")));

        verifier.verify(in);

        assertThat(cmd.getAllValues()).anyMatch(c -> c.contains("-Psystem-test verify"));
    }

    @Test
    void fallsBackToTheDefaultCommandWhenTheAppHasNone() {
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        when(bv.verify(any(), cmd.capture(), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        // ConsumerBuild(appId, dir) with no command → the reactor uses the bare default.
        verifier.verify(new ReactorInputs(Path.of("localrepo"), List.of(),
                List.of(new ConsumerBuild("app7576", Path.of("app")))));
        assertThat(cmd.getAllValues()).anyMatch(c -> c.startsWith(CascadeVerifier.DEFAULT_CONSUMER_COMMAND));
    }

    @Test
    void fallsBackToTheDefaultCommandWhenTheStoredCommandIsBlank() {
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        when(bv.verify(any(), cmd.capture(), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        // A blank (non-null) stored command must also fall back to the bare default.
        verifier.verify(new ReactorInputs(Path.of("localrepo"), List.of(),
                List.of(new ConsumerBuild("app7576", Path.of("app"), "   "))));
        assertThat(cmd.getAllValues()).anyMatch(c -> c.startsWith(CascadeVerifier.DEFAULT_CONSUMER_COMMAND));
    }

    @Test
    void aLongBuildOutputIsTailedInTheLogButReturnedInFull() {
        // A >2 KB output exercises the truncation branch of the log tail; the returned outputTail keeps the full text.
        String huge = "x".repeat(5000);
        when(bv.verify(any(), contains("install"), anyLong())).thenReturn(new BuildVerifier.BuildResult("PASS", ""));
        when(bv.verify(any(), contains(" test "), anyLong())).thenReturn(new BuildVerifier.BuildResult("FAIL", huge));
        ReactorResult r = verifier.verify(inputs());
        assertThat(r.passed()).isFalse();
        assertThat(r.outputTail()).hasSize(5000);   // the train still gets the whole log; only the console line is tailed
    }
}
