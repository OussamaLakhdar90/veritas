package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateDecisionRepository extends JpaRepository<GateDecision, String> {
    List<GateDecision> findByStatusOrderByCreatedAtDesc(String status);
}
