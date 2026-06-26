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
     * Snapshots whose generation claim is older than the lease but never finished or failed — i.e. a worker that
     * crashed mid-generation. A poll on these shows "Generating…" forever until they're failed; the reaper uses this
     * to mark them failed so the poll surfaces a clean error.
     */
    @Query("select s.id from FeatureIndexSnapshot s where s.generationStartedAt is not null "
            + "and s.generationStartedAt < :leaseCutoff and s.generatedStrategyId is null and s.generationError is null")
    List<String> findStaleInFlightIds(@Param("leaseCutoff") Instant leaseCutoff);

    /**
     * Set the generated-strategy audit link and clear the claim, as a column-scoped bulk update. Touching only
     * these two columns (not the whole entity) means it neither contends with the optimistic {@code @Version} of a
     * concurrent feature edit nor fails if the row was swept — it just affects 0 rows.
     */
    @Modifying
    @Query("update FeatureIndexSnapshot s set s.generatedStrategyId = :strategyId, s.generationStartedAt = null, "
            + "s.generationError = null where s.id = :id")
    int linkGenerated(@Param("id") String id, @Param("strategyId") String strategyId);

    /** Clear a generation claim (column-scoped bulk update; safe against a concurrent edit or a vanished row). */
    @Modifying
    @Query("update FeatureIndexSnapshot s set s.generationStartedAt = null where s.id = :id")
    int releaseClaim(@Param("id") String id);

    /**
     * Release the claim AND record the failure atomically (column-scoped bulk update). The async worker uses this so
     * a poller can tell "failed" ({@code generationError} set) from "never generated" — closing the race that a bare
     * {@code releaseClaim} leaves (claim cleared, looks identical to idle).
     */
    @Modifying
    @Query("update FeatureIndexSnapshot s set s.generationStartedAt = null, s.generationError = :error where s.id = :id")
    int failGeneration(@Param("id") String id, @Param("error") String error);
}
