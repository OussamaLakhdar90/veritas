package ca.bnc.qe.veritas.snyk;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykSnapshotRepository extends JpaRepository<SnykSnapshot, String> {

    /** The most recent snapshot for a watch — the baseline the next poll is diffed against. */
    Optional<SnykSnapshot> findFirstByWatchIdOrderByTakenAtDesc(String watchId);

    /** All watches' snapshots newest-first in ONE query — the caller keeps the first (latest) per watch. */
    List<SnykSnapshot> findByWatchIdInOrderByTakenAtDesc(Collection<String> watchIds);

    /** A watch's snapshots older than a cutoff — the retention sweep prunes these (keeping the latest baseline). */
    List<SnykSnapshot> findByWatchIdAndTakenAtBefore(String watchId, Instant cutoff);

    List<SnykSnapshot> findByWatchId(String watchId);

    void deleteByWatchId(String watchId);
}
