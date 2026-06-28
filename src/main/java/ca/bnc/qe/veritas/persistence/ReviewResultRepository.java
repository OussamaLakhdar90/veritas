package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewResultRepository extends JpaRepository<ReviewResult, String> {
    List<ReviewResult> findByTargetKeyOrderByCreatedAtDesc(String targetKey);

    /** Most-recent reviews across all targets — so prior verdicts are reopenable, not lost on navigation. */
    List<ReviewResult> findTop50ByOrderByCreatedAtDesc();
}
