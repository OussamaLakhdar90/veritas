package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

@ExtendWith(MockitoExtension.class)
class ScanReconcilerTest {

    @Mock ScanRepository scans;
    @InjectMocks ScanReconciler reconciler;

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
    void scheduledSweepFailsOnlyScansOlderThanTheCeiling() {
        ReflectionTestUtils.setField(reconciler, "maxRuntimeMinutes", 60L);
        Scan stale = new Scan();
        stale.setStatus(RunStatus.RUNNING);
        stale.setStartedAt(Instant.now().minus(120, ChronoUnit.MINUTES));
        Scan fresh = new Scan();
        fresh.setStatus(RunStatus.RUNNING);
        fresh.setStartedAt(Instant.now());
        lenient().when(scans.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(stale, fresh));

        reconciler.timeOutStaleScans();

        assertThat(stale.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(fresh.getStatus()).isEqualTo(RunStatus.RUNNING);   // still within the ceiling
    }
}
