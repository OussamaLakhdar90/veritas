package ca.bnc.qe.veritas.snyk;

import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One vulnerability captured in a {@link SnykSnapshot}: severity, the vulnerable {@code package@version}, its
 * CVE/CVSS, and — only when Snyk has a supported fix — the safe {@code fixedIn} version. {@code fixable=false}
 * marks the common "no supported fix" issues, which are tracked but not auto-upgradable.
 */
@Entity
@Table(name = "snyk_vuln", indexes = {
        @Index(name = "idx_snyk_vuln_snapshot", columnList = "snapshotId"),
        @Index(name = "idx_snyk_vuln_severity", columnList = "severity")
})
@Getter
@Setter
public class SnykVuln extends AuditableEntity {

    private String snapshotId;

    private String projectId;
    @Column(length = 500)
    private String projectName;   // e.g. profile-management/pom.xml

    private String issueId;
    private String severity;      // critical | high | medium | low
    @Column(length = 500)
    private String title;
    @Column(length = 500)
    private String pkgName;
    private String pkgVersion;
    private String cve;
    private String cwe;
    private double cvss;
    private int riskScore;
    private boolean fixable;
    /** The safe version to upgrade to when fixable, else null (no supported fix). */
    private String fixedIn;
}
