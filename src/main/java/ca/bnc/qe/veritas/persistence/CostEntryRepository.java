package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CostEntryRepository extends JpaRepository<CostEntry, String> {
    List<CostEntry> findAllByOrderByCreatedAtDesc();
    List<CostEntry> findBySkillOrderByCreatedAtDesc(String skill);

    long countBySkill(String skill);

    /** Total estimated USD spent by one skill (0 when none) — e.g. the Snyk fix module's breaking-change judge. */
    @Query("select coalesce(sum(c.estCostUsd), 0) from CostEntry c where c.skill = :skill")
    double sumEstCostUsdBySkill(@Param("skill") String skill);

    /** Retention sweep: bulk-delete cost-ledger rows older than the cutoff. */
    @Modifying(clearAutomatically = true)
    @Query("delete from CostEntry c where c.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
