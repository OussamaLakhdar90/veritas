package ca.bnc.qe.veritas.snyk.fix;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykFixTrainRepository extends JpaRepository<SnykFixTrain, String> {

    List<SnykFixTrain> findAllByOrderByStartedAtDesc();

    List<SnykFixTrain> findByStatus(String status);

    List<SnykFixTrain> findByStatusIn(List<String> statuses);

    /** Existing trains for a watch + coordinate — the submit dedup guard checks these for an in-flight one. */
    List<SnykFixTrain> findByWatchIdAndCoordinate(String watchId, String coordinate);

    /** All trains for a watch — used to cascade-delete them (and their steps) when the watch is removed. */
    List<SnykFixTrain> findByWatchId(String watchId);

    // Ops gauges (scrape-time counts).
    long countByStatus(String status);

    long countByStatusIn(List<String> statuses);

    long countByBreakingTrue();

    /** Terminal (finished) trains older than a cutoff — the retention sweep prunes these + their steps. */
    List<SnykFixTrain> findByFinishedAtBefore(Instant cutoff);
}
