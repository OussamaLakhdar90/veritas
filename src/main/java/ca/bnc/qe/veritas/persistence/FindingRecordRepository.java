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
     * Carry-forward lookup for a human's per-finding severity OVERRIDE across a scan's fingerprints: prior rows (a
     * different scan) that carry a {@code userSeverity}, newest first, so the caller keeps the most recent override
     * per fingerprint. Broader than {@link #findPriorDispositions} (any status), since a user may override severity
     * without accepting/rejecting the finding.
     */
    @Query("select f from FindingRecord f where f.fingerprint in :fingerprints and f.scanId <> :scanId "
            + "and f.userSeverity is not null order by f.createdAt desc")
    List<FindingRecord> findPriorUserSeverities(@Param("fingerprints") Collection<String> fingerprints,
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

    /**
     * Engine-Evolution signal collector: human severity votes on still-{@code UNSPECIFIED} findings, grouped by
     * (type, chosen severity, service). Joins finding → scan for the service dimension (there is no mapped
     * relationship, so it's a theta-join on {@code f.scanId = s.id}). Excludes findings a human dismissed
     * (REJECTED / FALSE_POSITIVE). Each row's {@code votes} is the DISTINCT-fingerprint count, so re-scans of the
     * same finding never inflate the tally.
     */
    @Query("select f.type as type, f.userSeverity as severity, s.serviceName as service, "
            + "count(distinct f.fingerprint) as votes "
            + "from FindingRecord f, Scan s "
            + "where f.scanId = s.id and f.severity = 'UNSPECIFIED' and f.userSeverity is not null "
            + "and (f.status is null or f.status not in ('REJECTED', 'FALSE_POSITIVE')) "
            + "group by f.type, f.userSeverity, s.serviceName")
    List<ClassificationVoteRow> findUnspecifiedClassificationVotes();

    /** Projection for {@link #findUnspecifiedClassificationVotes()} — one (type, chosen severity, service) tally. */
    interface ClassificationVoteRow {
        String getType();
        String getSeverity();
        String getService();
        long getVotes();
    }
}
