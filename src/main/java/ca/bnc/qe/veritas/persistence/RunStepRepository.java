package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunStepRepository extends JpaRepository<RunStep, String> {
    List<RunStep> findBySkillRunIdOrderByOrdinalAsc(String skillRunId);

    /** Retention sweep: bulk-delete run-step audit rows older than the cutoff. */
    @Modifying(clearAutomatically = true)
    @Query("delete from RunStep s where s.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
