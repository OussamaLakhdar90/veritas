package ca.bnc.qe.veritas.snyk.fix;

import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * The AI-derived reactor test command for one consumer app, cached so it is computed once and reused. Keyed by
 * (appId, repoSlug); invalidated when the app's build config changes — {@link #pomHash} fingerprints the pom(s) +
 * TestNG/JUnit suite files the command was derived from, so an edit to any of them forces a fresh derivation.
 */
@Entity
@Table(name = "app_build_command",
        uniqueConstraints = @UniqueConstraint(name = "uk_app_build_command", columnNames = {"appId", "repoSlug"}))
@Getter
@Setter
public class AppBuildCommand extends AuditableEntity {

    private String appId;
    private String repoSlug;

    /** SHA-256 (hex) of the build config the command was derived from — the cache-invalidation key. */
    @Column(length = 64)
    private String pomHash;

    /** The validated {@code mvn} command (allow-listed by {@link BuildCommandGuard}), without the reactor's repo arg. */
    @Column(length = 500)
    private String command;

    /** Short human-readable why, surfaced in the progress tracker + kept for audit. */
    @Column(length = 1000)
    private String rationale;
}
