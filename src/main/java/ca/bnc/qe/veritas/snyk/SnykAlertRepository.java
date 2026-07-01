package ca.bnc.qe.veritas.snyk;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykAlertRepository extends JpaRepository<SnykAlert, String> {

    List<SnykAlert> findAllByOrderByCreatedAtDesc();

    List<SnykAlert> findBySeenFalseOrderByCreatedAtDesc();
}
