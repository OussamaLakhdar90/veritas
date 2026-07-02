package ca.bnc.qe.veritas.snyk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.snyk.SnykClient;
import ca.bnc.qe.veritas.integration.snyk.SnykTarget;
import ca.bnc.qe.veritas.snyk.fix.FrameworkProperties;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import org.junit.jupiter.api.Test;

/** The app-id-centric watch: resolve the canonical application-tests target and watch it. */
class SnykServiceTest {

    private final SnykClient client = mock(SnykClient.class);
    private final SnykWatchRepository watches = mock(SnykWatchRepository.class);
    private final SnykSnapshotRepository snapshots = mock(SnykSnapshotRepository.class);
    private final SnykVulnRepository vulns = mock(SnykVulnRepository.class);
    private final SnykAlertRepository alerts = mock(SnykAlertRepository.class);
    private final SnykPollService pollService = mock(SnykPollService.class);
    private final SnykFixTrainRepository fixTrains = mock(SnykFixTrainRepository.class);
    private final SnykFixStepRepository fixSteps = mock(SnykFixStepRepository.class);
    private final SnykService service = new SnykService(
            client, watches, snapshots, vulns, alerts, pollService, new FrameworkProperties(), fixTrains, fixSteps);

    @Test
    void resolvesTheApplicationTestsTargetPreferringAnExactName() {
        when(client.listTargets("o1")).thenReturn(List.of(
                new SnykTarget("t0", "some-other-repo"), new SnykTarget("t1", "application-tests")));
        assertThat(service.resolveApplicationTestsTarget("o1")).get()
                .extracting(SnykTarget::id).isEqualTo("t1");
    }

    @Test
    void watchesAnAppByTargetingItsApplicationTestsRepo() {
        when(client.listTargets("o1")).thenReturn(List.of(new SnykTarget("t1", "application-tests")));
        when(watches.findByOrgIdAndTargetId("o1", "t1")).thenReturn(Optional.empty());
        when(watches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc(any())).thenReturn(Optional.empty());

        SnykWatchView v = service.addWatchForApp("o1", "app7576", "CIAM Profile");

        assertThat(v.repoSlug()).isEqualTo("application-tests");
        assertThat(v.orgSlug()).isEqualTo("app7576");
    }

    @Test
    void throwsWhenTheAppHasNoApplicationTestsRepo() {
        when(client.listTargets("o1")).thenReturn(List.of(new SnykTarget("t0", "unrelated-repo")));
        assertThatThrownBy(() -> service.addWatchForApp("o1", "app7576", "CIAM Profile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application-tests");
    }

    @Test
    void removeWatchCascadesToItsFixTrainsAndSteps() {
        SnykFixTrain train = new SnykFixTrain();
        train.setId("tr1");
        when(watches.existsById("w1")).thenReturn(true);
        when(snapshots.findByWatchId("w1")).thenReturn(List.of());
        when(fixTrains.findByWatchId("w1")).thenReturn(List.of(train));

        service.removeWatch("w1");

        // The watch's trains (and their steps) are deleted too, so they never orphan into the managerial summary.
        org.mockito.Mockito.verify(fixSteps).deleteByTrainId("tr1");
        org.mockito.Mockito.verify(fixTrains).deleteAll(List.of(train));
        org.mockito.Mockito.verify(watches).deleteById("w1");
    }

    @Test
    void removeWatchThrowsWhenUnknown() {
        when(watches.existsById("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.removeWatch("nope"))
                .isInstanceOf(ca.bnc.qe.veritas.skill.NotFoundException.class);
    }
}
