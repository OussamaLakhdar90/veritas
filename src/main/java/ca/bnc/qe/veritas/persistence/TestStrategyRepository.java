package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestStrategyRepository extends JpaRepository<TestStrategy, String> {

    /** serviceName → strategy count, for the service catalog (browse/recent-work). */
    @org.springframework.data.jpa.repository.Query("select e.serviceName as name, count(e) as count from TestStrategy e where e.serviceName is not null group by e.serviceName")
    List<ServiceCount> countByServiceName();

    List<TestStrategy> findByServiceNameOrderByCreatedAtDesc(String serviceName);
    List<TestStrategy> findByLineageIdOrderByVersionDesc(String lineageId);
}
