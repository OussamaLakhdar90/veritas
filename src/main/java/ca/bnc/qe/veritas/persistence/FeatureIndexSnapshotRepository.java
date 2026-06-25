package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureIndexSnapshotRepository extends JpaRepository<FeatureIndexSnapshot, String> {
    List<FeatureIndexSnapshot> findByServiceNameOrderByCreatedAtDesc(String serviceName);

    /**
     * The TTL sweep for session-scoped working state: delete snapshots idle (no edit/claim) since {@code cutoff},
     * but NEVER one whose generation is in flight — a claim newer than {@code leaseCutoff} is an active synthesis,
     * so it is excluded (a claim older than the lease is abandoned and may be reaped). Keyed on {@code updatedAt}
     * (last activity), so an actively-edited snapshot isn't swept out from under a live session.
     */
    @Modifying
    @Query("delete from FeatureIndexSnapshot s where s.updatedAt < :cutoff "
            + "and (s.generationStartedAt is null or s.generationStartedAt < :leaseCutoff)")
    int deleteIdleBefore(@Param("cutoff") Instant cutoff, @Param("leaseCutoff") Instant leaseCutoff);

    /**
     * Set the generated-strategy audit link and clear the claim, as a column-scoped bulk update. Touching only
     * these two columns (not the whole entity) means it neither contends with the optimistic {@code @Version} of a
     * concurrent feature edit nor fails if the row was swept — it just affects 0 rows.
     */
    @Modifying
    @Query("update FeatureIndexSnapshot s set s.generatedStrategyId = :strategyId, s.generationStartedAt = null "
            + "where s.id = :id")
    int linkGenerated(@Param("id") String id, @Param("strategyId") String strategyId);

    /** Clear a generation claim (column-scoped bulk update; safe against a concurrent edit or a vanished row). */
    @Modifying
    @Query("update FeatureIndexSnapshot s set s.generationStartedAt = null where s.id = :id")
    int releaseClaim(@Param("id") String id);
}
