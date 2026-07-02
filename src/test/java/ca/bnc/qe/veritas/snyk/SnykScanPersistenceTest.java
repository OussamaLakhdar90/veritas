package ca.bnc.qe.veritas.snyk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The atomic snapshot+vuln write must stamp every vuln with the saved snapshot id (or they orphan). */
class SnykScanPersistenceTest {

    private final SnykSnapshotRepository snapshots = mock(SnykSnapshotRepository.class);
    private final SnykVulnRepository vulns = mock(SnykVulnRepository.class);
    private final SnykScanPersistence persistence = new SnykScanPersistence(snapshots, vulns);

    @Test
    void stampsEveryVulnWithThePersistedSnapshotId() {
        SnykSnapshot snap = new SnykSnapshot();
        SnykSnapshot saved = new SnykSnapshot();
        saved.setId("snap-1");
        when(snapshots.save(snap)).thenReturn(saved);
        SnykVuln a = new SnykVuln();
        SnykVuln b = new SnykVuln();

        SnykSnapshot result = persistence.save(snap, List.of(a, b));

        assertThat(result.getId()).isEqualTo("snap-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SnykVuln>> cap = ArgumentCaptor.forClass(List.class);
        verify(vulns).saveAll(cap.capture());
        assertThat(cap.getValue()).allSatisfy(v -> assertThat(v.getSnapshotId()).isEqualTo("snap-1"));
    }
}
