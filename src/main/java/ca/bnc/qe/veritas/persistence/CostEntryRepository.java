package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostEntryRepository extends JpaRepository<CostEntry, String> {
    List<CostEntry> findAllByOrderByCreatedAtDesc();
    List<CostEntry> findBySkillOrderByCreatedAtDesc(String skill);
}
