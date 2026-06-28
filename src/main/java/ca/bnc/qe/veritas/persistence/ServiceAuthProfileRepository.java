package ca.bnc.qe.veritas.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceAuthProfileRepository extends JpaRepository<ServiceAuthProfile, String> {

    /** The latest declared auth profile for a service repo (one per appId + slug; newest wins). */
    Optional<ServiceAuthProfile> findFirstByAppIdAndServiceRepoSlugOrderByUpdatedAtDesc(String appId,
                                                                                        String serviceRepoSlug);
}
