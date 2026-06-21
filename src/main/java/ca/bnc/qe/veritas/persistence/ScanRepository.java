package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRepository extends JpaRepository<Scan, String> {
    List<Scan> findAllByOrderByStartedAtDesc();
    List<Scan> findByServiceNameOrderByStartedAtDesc(String serviceName);
}
