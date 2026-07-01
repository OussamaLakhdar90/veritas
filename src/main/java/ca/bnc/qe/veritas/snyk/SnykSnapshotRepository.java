package ca.bnc.qe.veritas.snyk;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykSnapshotRepository extends JpaRepository<SnykSnapshot, String> {

    /** The most recent snapshot for a watch — the baseline the next poll is diffed against. */
    Optional<SnykSnapshot> findFirstByWatchIdOrderByTakenAtDesc(String watchId);
}
