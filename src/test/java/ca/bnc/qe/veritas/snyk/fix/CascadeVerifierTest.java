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
}
