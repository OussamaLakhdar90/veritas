package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestStrategyRepository extends JpaRepository<TestStrategy, String> {
    List<TestStrategy> findByServiceNameOrderByCreatedAtDesc(String serviceName);
    List<TestStrategy> findByLineageIdOrderByVersionDesc(String lineageId);
}
