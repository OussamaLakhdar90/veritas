package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A service's declared auth-token profile — the {@code ServiceAuthSpec} JSON — keyed by {@code appId +
 * serviceRepoSlug}, so the test-gen wizard can pre-fill it on the next run. Stores only env-var <strong>names</strong>,
 * mechanisms, and path mappings; <strong>never a secret value</strong>.
 */
@Entity
@Table(name = "service_auth_profile")
@Getter
@Setter
public class ServiceAuthProfile extends AuditableEntity {

    private String appId;
    private String serviceRepoSlug;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String spec;   // ServiceAuthSpec serialized as JSON (names only, no secrets)
}
