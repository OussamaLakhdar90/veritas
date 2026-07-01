package ca.bnc.qe.veritas.snyk;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykVulnRepository extends JpaRepository<SnykVuln, String> {

    List<SnykVuln> findBySnapshotId(String snapshotId);
}
