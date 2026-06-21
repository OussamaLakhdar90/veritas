package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, String> {
    List<TestCase> findByServiceNameOrderByCreatedAtDesc(String serviceName);
}
