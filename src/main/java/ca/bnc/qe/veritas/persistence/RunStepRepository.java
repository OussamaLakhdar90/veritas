package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunStepRepository extends JpaRepository<RunStep, String> {
    List<RunStep> findBySkillRunIdOrderByOrdinalAsc(String skillRunId);
}
