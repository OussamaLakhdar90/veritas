package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FindingRecordRepository extends JpaRepository<FindingRecord, String> {
    List<FindingRecord> findByScanIdOrderBySeverityAsc(String scanId);
    List<FindingRecord> findByScanId(String scanId);
    List<FindingRecord> findByFingerprintOrderByCreatedAtDesc(String fingerprint);

    /**
     * One query for the carry-forward disposition lookup across a whole scan's fingerprints (replaces the per-finding
     * N+1). Returns prior dispositioned findings (a different scan, a non-OPEN status), newest first, so the caller
     * keeps the first row per fingerprint as the most recent disposition.
     */
    @Query("select f from FindingRecord f where f.fingerprint in :fingerprints and f.scanId <> :scanId "
            + "and f.status is not null and f.status <> 'OPEN' order by f.createdAt desc")
    List<FindingRecord> findPriorDispositions(@Param("fingerprints") Collection<String> fingerprints,
                                              @Param("scanId") String scanId);

    /**
     * Executive rollup: every DISTINCT breaking defect ever caught (fingerprints repeat across re-scans —
     * the carry-forward dedup), minus what a human dismissed as rejected / false positive.
     */
    @Query("select count(distinct f.fingerprint) from FindingRecord f where f.type in :types "
            + "and (f.status is null or f.status not in ('REJECTED', 'FALSE_POSITIVE'))")
    long countDistinctCaughtByTypes(@Param("types") Collection<String> types);

    /** Retention sweep: bulk-delete finding rows older than the cutoff. */
    @Modifying(clearAutomatically = true)
    @Query("delete from FindingRecord f where f.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
