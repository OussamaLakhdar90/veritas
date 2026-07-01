package ca.bnc.qe.veritas.snyk;

import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A Snyk repository the user chose to watch: one Snyk <b>target</b> (a Bitbucket repo) inside an <b>org</b>
 * (a BNC app-id). The poller reads every project (pom) under it and raises a {@link SnykAlert} when the
 * vulnerability status worsens. Uniqueness is (orgId, targetId).
 */
@Entity
@Table(name = "snyk_watch", indexes = @Index(name = "idx_snyk_watch_enabled", columnList = "enabled"))
@Getter
@Setter
public class SnykWatch extends AuditableEntity {

    private String orgId;
    private String orgSlug;      // e.g. app7576 — the "application id" the user selects
    @Column(length = 300)
    private String orgName;

    private String targetId;
    private String repoSlug;     // the target display name, e.g. application-tests

    private boolean enabled = true;
}
