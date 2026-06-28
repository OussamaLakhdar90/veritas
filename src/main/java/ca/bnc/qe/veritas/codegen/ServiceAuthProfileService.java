package ca.bnc.qe.veritas.codegen;

import ca.bnc.qe.veritas.persistence.ServiceAuthProfile;
import ca.bnc.qe.veritas.persistence.ServiceAuthProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Persists a service's declared auth-token profile so the wizard pre-fills it next run — keyed by {@code appId +
 * serviceRepoSlug}. Stores only env-var <strong>names</strong>, mechanisms, and path mappings — never a secret value.
 */
@Service
@Slf4j
public class ServiceAuthProfileService {

    private final ServiceAuthProfileRepository repo;
    private final ObjectMapper objectMapper;

    public ServiceAuthProfileService(ServiceAuthProfileRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** The saved profile for (appId, serviceRepoSlug), or an empty (public) spec when none is stored / unreadable. */
    public ServiceAuthSpec find(String appId, String serviceRepoSlug) {
        if (isBlank(serviceRepoSlug)) {
            return ServiceAuthSpec.none();
        }
        return repo.findFirstByAppIdAndServiceRepoSlugOrderByUpdatedAtDesc(appId, serviceRepoSlug)
                .map(ServiceAuthProfile::getSpec)
                .map(this::read)
                .orElseGet(ServiceAuthSpec::none);
    }

    /** Upsert the profile. No-op when there's no repo slug (local-path dev) or the spec is null. Stores names only. */
    public void save(String appId, String serviceRepoSlug, ServiceAuthSpec spec) {
        if (isBlank(serviceRepoSlug) || spec == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(spec);
            ServiceAuthProfile p = repo.findFirstByAppIdAndServiceRepoSlugOrderByUpdatedAtDesc(appId, serviceRepoSlug)
                    .orElseGet(ServiceAuthProfile::new);
            p.setAppId(appId);
            p.setServiceRepoSlug(serviceRepoSlug);
            p.setSpec(json);
            repo.save(p);
        } catch (Exception e) {
            log.warn("Could not persist auth profile for {}/{}: {}", appId, serviceRepoSlug, e.getMessage());
        }
    }

    private ServiceAuthSpec read(String json) {
        try {
            return objectMapper.readValue(json, ServiceAuthSpec.class);
        } catch (Exception e) {
            log.warn("Unreadable auth-profile json: {}", e.getMessage());
            return ServiceAuthSpec.none();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
