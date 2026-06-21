package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestPlanRepository extends JpaRepository<TestPlan, String> {
    List<TestPlan> findByServiceNameOrderByCreatedAtDesc(String serviceName);
    List<TestPlan> findAllByOrderByCreatedAtDesc();
}
