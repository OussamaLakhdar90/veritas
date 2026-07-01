package ca.bnc.qe.veritas.snyk;

import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A dashboard notification raised when a watched repo's vulnerability status worsens (0 → N, or a new Critical).
 * {@code severity} drives the colour (critical = red). {@code seen} is flipped once the user acknowledges it.
 */
@Entity
@Table(name = "snyk_alert", indexes = {
        @Index(name = "idx_snyk_alert_watch", columnList = "watchId"),
        @Index(name = "idx_snyk_alert_seen", columnList = "seen")
})
@Getter
@Setter
public class SnykAlert extends AuditableEntity {

    private String watchId;
    private String orgSlug;
    private String repoSlug;

    private String severity;      // worst new severity: critical | high | medium | low
    @Column(length = 1000)
    private String message;

    private boolean seen;
}
