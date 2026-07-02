package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** The stale-timeout is based on the row's last WRITE (heartbeat), never submit time — a queued scan or a long
 *  scan still streaming progress must never be falsely failed; a truly hung one is interrupted AND failed. */
@ExtendWith(MockitoExtension.class)
class ScanReconcilerTest {

    @Mock ScanRepository scans;
    @Mock AsyncScanRunner runner;
    @InjectMocks ScanReconciler reconciler;

    private static Scan running(String stage, Instant startedAt, Instant updatedAt) {
        Scan s = new Scan();
        s.setStatus(RunStatus.RUNNING);
        s.setStage(stage);
        s.setStartedAt(startedAt);
        s.setUpdatedAt(updatedAt);
        return s;
    }

    @Test
    void startupMarksInterruptedRunningScansFailed() {
        Scan running = new Scan();
        running.setStatus(RunStatus.RUNNING);
        when(scans.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(running));

        reconciler.recoverInterruptedScans();

        assertThat(running.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(running.getErrorMessage()).contains("interrupted");
    }

    @Test
    void sweepFailsHungScanAndInterruptsItsWorker() {
        ReflectionTestUtils.setField(reconciler, "maxRuntimeMinutes", 60L);
        Instant old = Instant.now().minus(120, ChronoUnit.MINUTES);
        Scan hung = running(ScanStages.RECONCILING, old, old);   // no write in 2 hours → truly stuck
        lenient().when(scans.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(hung));

        reconciler.timeOutStaleScans();

        assertThat(hung.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(hung.getErrorMessage()).contains("no progress");
        verify(runner).cancel(hung.getId());   // frees the pool slot for queued scans
    }

    @Test
    void sweepSparesLongRunningScanThatIsStillMakingProgress() {
        // THE regression this file guards: a scan submitted 3h ago but actively streaming LLM output (fresh
        // heartbeat) used to be killed mid-stream, discarding its findings and Copilot spend.
        ReflectionTestUtils.setField(reconciler, "maxRuntimeMinutes", 60L);
        Scan longButAlive = running(ScanStages.RECONCILING,
                Instant.now().minus(180, ChronoUnit.MINUTES), Instant.now());
        lenient().when(scans.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(longButAlive));

        reconciler.timeOutStaleScans();

        assertThat(longButAlive.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(runner, never()).cancel(longButAlive.getId());
    }

    @Test
    void sweepNeverTimesOutAScanStillWaitingInTheQueue() {
        // Queue wait used to count against the ceiling — a scan behind two long ones was failed without ever
        // running. QUEUED-stage scans are exempt; they run as soon as a slot frees.
        ReflectionTestUtils.setField(reconciler, "maxRuntimeMinutes", 60L);
        Instant old = Instant.now().minus(120, ChronoUnit.MINUTES);
        Scan queued = running(ScanStages.QUEUED, old, old);
        lenient().when(scans.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(queued));

        reconciler.timeOutStaleScans();

        assertThat(queued.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(runner, never()).cancel(queued.getId());
    }
}
