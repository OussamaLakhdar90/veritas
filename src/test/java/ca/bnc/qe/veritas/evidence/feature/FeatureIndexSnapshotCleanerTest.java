package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
}
