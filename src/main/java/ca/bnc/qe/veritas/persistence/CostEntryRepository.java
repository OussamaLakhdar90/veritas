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

    /** Retention sweep: bulk-delete cost-ledger rows older than the cutoff. */
    @Modifying(clearAutomatically = true)
    @Query("delete from CostEntry c where c.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
