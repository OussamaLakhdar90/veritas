package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRunRepository extends JpaRepository<SkillRun, String> {
    List<SkillRun> findBySkillNameOrderByStartedAtDesc(String skillName);
    List<SkillRun> findAllByOrderByStartedAtDesc();
}
