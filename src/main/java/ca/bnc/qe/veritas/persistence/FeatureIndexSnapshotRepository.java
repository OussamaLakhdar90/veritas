package ca.bnc.qe.veritas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureIndexSnapshotRepository extends JpaRepository<FeatureIndexSnapshot, String> {
    List<FeatureIndexSnapshot> findByServiceNameOrderByCreatedAtDesc(String serviceName);
}
