package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodegenRunRepository extends JpaRepository<CodegenRun, String> {

    /** serviceName → codegen-run count, for the service catalog (browse/recent-work). */
    @org.springframework.data.jpa.repository.Query("select e.serviceName as name, count(e) as count from CodegenRun e where e.serviceName is not null group by e.serviceName")
    List<ServiceCount> countByServiceName();

    List<CodegenRun> findByServiceNameOrderByCreatedAtDesc(String serviceName);
}
