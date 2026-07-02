package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** The reconciler fails interrupted/stale IN_FLIGHT trains but never touches human-wait states. */
class SnykFixReconcilerTest {

    private final SnykFixTrainRepository trains = mock(SnykFixTrainRepository.class);
    private final SnykFixReconciler reconciler = new SnykFixReconciler(trains);

    private SnykFixTrain train(String id, String status, Instant startedAt) {
        SnykFixTrain t = new SnykFixTrain();
        t.setId(id);
        t.setStatus(status);
        t.setStartedAt(startedAt);
        return t;
    }

    @Test
    void interruptedInFlightTrainsAreFailedOnStartup() {
        SnykFixTrain stuck = train("t1", SnykFixStatus.VERIFYING, Instant.now());
        when(trains.findByStatusIn(anyList())).thenReturn(List.of(stuck));

        reconciler.recoverInterrupted();

        assertThat(stuck.getStatus()).isEqualTo(SnykFixStatus.FAILED);
        assertThat(stuck.getFailedStage()).isEqualTo(SnykFixStatus.VERIFYING);
        verify(trains).save(stuck);
    }

    @Test
    void theInFlightSetExcludesTheHumanWaitStates() {
        when(trains.findByStatusIn(anyList())).thenReturn(List.of());
        reconciler.recoverInterrupted();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
        verify(trains).findByStatusIn(cap.capture());
        // AWAITING_MANUAL_FIX / PR_OPEN / AWAITING_CONFIRM may legitimately wait days — never timed out.
        assertThat(cap.getValue())
                .doesNotContain(SnykFixStatus.AWAITING_MANUAL_FIX, SnykFixStatus.PR_OPEN, SnykFixStatus.DONE);
    }

    @Test
    void aStaleTrainPastTheMaxRuntimeIsFailedButAFreshOneIsNot() {
        ReflectionTestUtils.setField(reconciler, "maxRuntimeMinutes", 30L);
        SnykFixTrain stale = train("old", SnykFixStatus.PLANNING, Instant.now().minus(60, ChronoUnit.MINUTES));
        SnykFixTrain fresh = train("new", SnykFixStatus.PLANNING, Instant.now());
        when(trains.findByStatusIn(anyList())).thenReturn(List.of(stale, fresh));

        reconciler.timeoutStale();

        assertThat(stale.getStatus()).isEqualTo(SnykFixStatus.FAILED);
        assertThat(fresh.getStatus()).isEqualTo(SnykFixStatus.PLANNING);   // within the runtime window — untouched
        verify(trains).save(stale);
        verify(trains, never()).save(fresh);
    }
}
