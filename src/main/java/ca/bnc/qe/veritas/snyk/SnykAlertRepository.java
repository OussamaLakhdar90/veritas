package ca.bnc.qe.veritas.snyk;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykAlertRepository extends JpaRepository<SnykAlert, String> {

    List<SnykAlert> findAllByOrderByCreatedAtDesc();

    List<SnykAlert> findBySeenFalseOrderByCreatedAtDesc();

    /** Retention: drop already-seen alerts older than a cutoff. */
    int deleteBySeenTrueAndCreatedAtBefore(Instant cutoff);

    void deleteByWatchId(String watchId);
}
