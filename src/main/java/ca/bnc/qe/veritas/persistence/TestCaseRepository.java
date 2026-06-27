package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, String> {

    /** serviceName → test-case count, for the service catalog (browse/recent-work). */
    @org.springframework.data.jpa.repository.Query("select e.serviceName as name, count(e) as count from TestCase e where e.serviceName is not null group by e.serviceName")
    List<ServiceCount> countByServiceName();

    List<TestCase> findByServiceNameOrderByCreatedAtDesc(String serviceName);
}
