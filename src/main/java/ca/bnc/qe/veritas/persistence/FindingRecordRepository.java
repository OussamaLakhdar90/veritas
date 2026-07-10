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
     * Engine-Evolution signal collector: one row per human severity override on a still-{@code UNSPECIFIED}
     * finding, newest first. Joins finding → scan for the service dimension (there is no mapped relationship, so
     * it's a theta-join on {@code f.scanId = s.id}). Excludes findings a human dismissed (REJECTED / FALSE_POSITIVE)
     * and rows whose scan has no service name (they can't prove cross-project convergence). Returns raw rows — NOT a
     * grouped tally — because the caller must dedupe by {@code fingerprint} (keeping the newest override) to honour
     * one-finding = one-vote; grouping in SQL would count a finding whose override changed across re-scans twice.
     */
    @Query("select f.type as type, f.fingerprint as fingerprint, f.userSeverity as severity, "
            + "s.serviceName as service "
            + "from FindingRecord f, Scan s "
            + "where f.scanId = s.id and f.severity = 'UNSPECIFIED' and f.userSeverity is not null "
            + "and s.serviceName is not null and s.serviceName <> '' "
            + "and (f.status is null or f.status not in ('REJECTED', 'FALSE_POSITIVE')) "
            + "order by f.createdAt desc")
    List<ClassificationVoteRow> findUnspecifiedClassificationVotes();

    /** Projection for {@link #findUnspecifiedClassificationVotes()} — one finding row (dedupe by fingerprint). */
    interface ClassificationVoteRow {
        String getType();
        String getFingerprint();
        String getSeverity();
        String getService();
    }
}
