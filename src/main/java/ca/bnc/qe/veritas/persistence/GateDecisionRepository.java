package ca.bnc.qe.veritas.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateDecisionRepository extends JpaRepository<GateDecision, String> {
    List<GateDecision> findByStatusOrderByCreatedAtDesc(String status);

    /** Most-recent gate for an action in a given state — used to resume an approved write or reuse a pending one. */
    Optional<GateDecision> findFirstByRunIdAndActionAndStatusOrderByCreatedAtDesc(String runId, String action, String status);
}
