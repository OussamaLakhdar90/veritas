package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ScanRepository extends JpaRepository<Scan, String> {
    List<Scan> findAllByOrderByStartedAtDesc();
    List<Scan> findByServiceNameOrderByStartedAtDesc(String serviceName);
    List<Scan> findByStatus(RunStatus status);

    /** One-time migration: rows created before the @Version column existed have NULL version; set them to 0 so a
     *  nullable Long version doesn't make Spring Data treat them as new entities. Returns the number updated. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE scan SET version = 0 WHERE version IS NULL", nativeQuery = true)
    int backfillNullVersions();
}
