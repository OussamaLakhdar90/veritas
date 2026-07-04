package ca.bnc.qe.veritas.snyk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private final AsyncSnykRefreshRunner refreshRunner = mock(AsyncSnykRefreshRunner.class);
    private final SnykFixTrainRepository fixTrains = mock(SnykFixTrainRepository.class);
    private final SnykFixStepRepository fixSteps = mock(SnykFixStepRepository.class);
    private final SnykService service = new SnykService(
            client, watches, snapshots, vulns, alerts, pollService, refreshRunner, new FrameworkProperties(),
            fixTrains, fixSteps);

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
        // The row is saved synchronously (so the UI sees the new watch at once); its initial poll runs in the
        // background — never on the request thread that added the watch.
        verify(refreshRunner).pollNewWatch(any(SnykWatch.class));
        verify(pollService, never()).poll(any());
    }

    @Test
    void refreshAllQueuesTheBackgroundPollAndReturnsTheEnabledCountWithoutBlocking() {
        when(watches.countByEnabledTrue()).thenReturn(3L);

        int queued = service.refreshAll();

        assertThat(queued).isEqualTo(3);
        // The slow Snyk REST work is handed to the background runner — the request thread never calls pollAll itself.
        verify(refreshRunner).refreshAll();
        verify(pollService, never()).pollAll();
    }

    @Test
    void refreshOneQueuesTheBackgroundPollForAKnownWatch() {
        when(watches.existsById("w1")).thenReturn(true);

        service.refresh("w1");

        verify(refreshRunner).refresh("w1");
        verify(pollService, never()).poll(any());
    }

    @Test
    void refreshOneThrowsWhenTheWatchIsUnknown() {
        when(watches.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.refresh("nope"))
                .isInstanceOf(ca.bnc.qe.veritas.skill.NotFoundException.class);
        verify(refreshRunner, never()).refresh(any());
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
        org.mockito.Mockito.verify(pollService).forgetWatch("w1");   // release its poll lock so the lock map doesn't leak
    }

    @Test
    void removeWatchThrowsWhenUnknown() {
        when(watches.existsById("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.removeWatch("nope"))
                .isInstanceOf(ca.bnc.qe.veritas.skill.NotFoundException.class);
    }
}
