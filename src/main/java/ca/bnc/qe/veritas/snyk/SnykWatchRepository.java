package ca.bnc.qe.veritas.snyk;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnykWatchRepository extends JpaRepository<SnykWatch, String> {

    List<SnykWatch> findByEnabledTrue();

    Optional<SnykWatch> findByOrgIdAndTargetId(String orgId, String targetId);
}
