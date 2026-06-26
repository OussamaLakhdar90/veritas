package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The orphan-snapshot TTL sweep deletes by an idle cutoff (one TTL ago) while excluding in-flight claims (one lease ago). */
class FeatureIndexSnapshotCleanerTest {

    @Test
    void sweepDeletesIdleSnapshotsButExcludesInFlightClaims() {
        FeatureIndexSnapshotRepository repository = mock(FeatureIndexSnapshotRepository.class);
        when(repository.deleteIdleBefore(any(), any())).thenReturn(3);
        FeatureIndexSnapshotCleaner cleaner = new FeatureIndexSnapshotCleaner(repository, 168, 15);

        cleaner.sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> leaseCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteIdleBefore(cutoff.capture(), leaseCutoff.capture());

        Instant oneTtlAgo = Instant.now().minus(Duration.ofHours(168));
        Instant oneLeaseAgo = Instant.now().minus(Duration.ofMinutes(15));
        assertThat(cutoff.getValue()).isBetween(oneTtlAgo.minusSeconds(30), oneTtlAgo.plusSeconds(30));
        assertThat(leaseCutoff.getValue()).isBetween(oneLeaseAgo.minusSeconds(30), oneLeaseAgo.plusSeconds(30));
    }

    @Test
    void sweepFailsGenerationsLeftInFlightByACrashedWorker() {
        FeatureIndexSnapshotRepository repository = mock(FeatureIndexSnapshotRepository.class);
        when(repository.findStaleInFlightIds(any())).thenReturn(List.of("snap-1", "snap-2"));
        FeatureIndexSnapshotCleaner cleaner = new FeatureIndexSnapshotCleaner(repository, 168, 15);

        cleaner.sweep();

        // Each stuck generation is marked failed so a poll surfaces a clean error instead of spinning forever.
        verify(repository).failGeneration(eq("snap-1"), any());
        verify(repository).failGeneration(eq("snap-2"), any());
    }
}
