package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodegenRunRepository extends JpaRepository<CodegenRun, String> {
    List<CodegenRun> findByServiceNameOrderByCreatedAtDesc(String serviceName);
}
