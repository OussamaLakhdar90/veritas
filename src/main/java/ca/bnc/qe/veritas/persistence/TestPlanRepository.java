package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestPlanRepository extends JpaRepository<TestPlan, String> {

    /** serviceName → release-plan count, for the service catalog (browse/recent-work). */
    @org.springframework.data.jpa.repository.Query("select e.serviceName as name, count(e) as count from TestPlan e where e.serviceName is not null group by e.serviceName")
    List<ServiceCount> countByServiceName();

    List<TestPlan> findByServiceNameOrderByCreatedAtDesc(String serviceName);
    List<TestPlan> findAllByOrderByCreatedAtDesc();
}
