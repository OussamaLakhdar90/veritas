package ca.bnc.qe.veritas.snyk;

import java.time.Instant;
import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A point-in-time reading of one watch's vulnerabilities: per-severity counts across every project (pom) under
 * the watched repo, plus how many are fixable. The individual issues hang off it as {@link SnykVuln} rows. The
 * poller compares the newest two snapshots to detect a worsening status (0 → N, or a new Critical).
 */
@Entity
@Table(name = "snyk_snapshot", indexes = @Index(name = "idx_snyk_snapshot_watch", columnList = "watchId"))
@Getter
@Setter
public class SnykSnapshot extends AuditableEntity {

    private String watchId;
    private Instant takenAt;

    private int critical;
    private int high;
    private int medium;
    private int low;

    private int projectCount;
    private int fixableCount;

    public int total() {
        return critical + high + medium + low;
    }
}
