package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Persisted form of a {@code finding.Finding}: evidence + current/proposed YAML + status, per scan. */
@Entity
@Table(name = "finding", indexes = {
        @Index(name = "idx_finding_scan", columnList = "scanId"),
        @Index(name = "idx_finding_severity", columnList = "severity")
})
@Getter
@Setter
public class FindingRecord extends AuditableEntity {

    private String scanId;
    private String fingerprint;     // stable id for status carry-forward across scans

    private String type;
    private String layer;
    private String severity;
    private String confidence;
    private String origin;          // DETERMINISTIC | LLM

    private String endpoint;
    private String specSource;

    @Column(length = 2000)
    private String summary;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String explanation;

    private String codeFile;
    private Integer codeStartLine;
    private Integer codeEndLine;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String codeSnippet;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String currentYamlFragment;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String proposedFix;

    private String citation;
    private String status;          // OPEN | TRIAGED | ACCEPTED | REJECTED | JIRA_CREATED | FIXED | WONT_FIX | FALSE_POSITIVE

    // Disposition audit trail: who set the current status, when, and why (carried forward across re-scans).
    private String reviewedBy;
    private Instant reviewedAt;
    @Column(length = 1000)
    private String reviewNote;
}
