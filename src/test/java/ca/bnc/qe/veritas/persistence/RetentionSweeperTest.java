package ca.bnc.qe.veritas.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Pure-Mockito coverage of the retention sweep: it deletes from each table per its TTL, and a TTL of 0 disables it. */
class RetentionSweeperTest {

    private final FindingRecordRepository findings = mock(FindingRecordRepository.class);
    private final CostEntryRepository costs = mock(CostEntryRepository.class);
    private final RunStepRepository runSteps = mock(RunStepRepository.class);

    @Test
    void sweepsEachTableWithACutoffInThePast() {
        when(findings.deleteByCreatedAtBefore(any())).thenReturn(3);
        when(costs.deleteByCreatedAtBefore(any())).thenReturn(0);
        when(runSteps.deleteByCreatedAtBefore(any())).thenReturn(1);
        Instant before = Instant.now();
        new RetentionSweeper(findings, costs, runSteps, 180, 365, 90).sweep();

        ArgumentCaptor<Instant> findingCut = ArgumentCaptor.forClass(Instant.class);
        verify(findings).deleteByCreatedAtBefore(findingCut.capture());
        // 180-day cutoff is well in the past (older than 100 days before this test started).
        org.assertj.core.api.Assertions.assertThat(findingCut.getValue())
                .isBefore(before.minus(java.time.Duration.ofDays(100)));
        verify(costs).deleteByCreatedAtBefore(any());
        verify(runSteps).deleteByCreatedAtBefore(any());
    }

    @Test
    void aZeroTtlDisablesTheSweepForThatTable() {
        // findings disabled (0), cost + run-step enabled.
        new RetentionSweeper(findings, costs, runSteps, 0, 365, 90).sweep();
        verify(findings, never()).deleteByCreatedAtBefore(any());
        verify(costs).deleteByCreatedAtBefore(any());
        verify(runSteps).deleteByCreatedAtBefore(any());
    }
}
