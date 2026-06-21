package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewResultRepository extends JpaRepository<ReviewResult, String> {
    List<ReviewResult> findByTargetKeyOrderByCreatedAtDesc(String targetKey);
}
