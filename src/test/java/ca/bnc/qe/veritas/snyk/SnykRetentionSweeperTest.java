package ca.bnc.qe.veritas.snyk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStepRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import org.junit.jupiter.api.Test;

/** Retention keeps the latest snapshot per watch as the diff baseline and prunes the rest + seen alerts + old trains. */
class SnykRetentionSweeperTest {

    private final SnykWatchRepository watches = mock(SnykWatchRepository.class);
    private final SnykSnapshotRepository snapshots = mock(SnykSnapshotRepository.class);
    private final SnykVulnRepository vulns = mock(SnykVulnRepository.class);
    private final SnykAlertRepository alerts = mock(SnykAlertRepository.class);
    private final SnykFixTrainRepository trains = mock(SnykFixTrainRepository.class);
    private final SnykFixStepRepository steps = mock(SnykFixStepRepository.class);
    private final SnykRetentionSweeper sweeper =
            new SnykRetentionSweeper(watches, snapshots, vulns, alerts, trains, steps, 30, 60, 90);

    private SnykSnapshot snap(String id) {
        SnykSnapshot s = new SnykSnapshot();
        s.setId(id);
        return s;
    }

    @Test
    void keepsTheLatestSnapshotPerWatchAndPrunesOlderOnesWithTheirVulns() {
        SnykWatch w = new SnykWatch();
        w.setId("w1");
        SnykSnapshot latest = snap("s-latest");
        SnykSnapshot old = snap("s-old");
        when(watches.findAll()).thenReturn(List.of(w));
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc("w1")).thenReturn(Optional.of(latest));
        when(snapshots.findByWatchIdAndTakenAtBefore(eq("w1"), any())).thenReturn(List.of(old, latest));
        when(trains.findByFinishedAtBefore(any())).thenReturn(List.of());

        sweeper.sweep();

        verify(vulns).deleteBySnapshotId("s-old");
        verify(snapshots).delete(old);
        verify(snapshots, never()).delete(latest);   // the baseline is kept even though it's older than the cutoff
        verify(alerts).deleteBySeenTrueAndCreatedAtBefore(any());
    }

    @Test
    void prunesOldFailedFixTrainsWithTheirSteps() {
        when(watches.findAll()).thenReturn(List.of());
        SnykFixTrain t = new SnykFixTrain();
        t.setId("t1");
        t.setStatus(SnykFixStatus.FAILED);
        when(trains.findByFinishedAtBefore(any())).thenReturn(List.of(t));

        sweeper.sweep();

        verify(steps).deleteByTrainId("t1");
        verify(trains).delete(t);
    }

    @Test
    void keepsDoneFixTrainsSoTheExecCardFixCountsDoNotShrink() {
        when(watches.findAll()).thenReturn(List.of());
        SnykFixTrain done = new SnykFixTrain();
        done.setId("t-done");
        done.setStatus(SnykFixStatus.DONE);   // a merged fix — the managerial "fixes merged / PRs opened" record
        when(trains.findByFinishedAtBefore(any())).thenReturn(List.of(done));

        sweeper.sweep();

        verify(steps, never()).deleteByTrainId("t-done");
        verify(trains, never()).delete(done);
    }
}
