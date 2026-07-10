package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/** Persisted form of a {@code finding.Finding}: evidence + current/proposed YAML + status, per scan. */
@Entity
@Table(name = "finding", indexes = {
        @Index(name = "idx_finding_scan", columnList = "scanId"),
        @Index(name = "idx_finding_severity", columnList = "severity"),
        // Backs the carry-forward disposition lookup (findPriorDispositions / findByFingerprint…) which would
        // otherwise full-scan the finding table once per re-scan as it grows.
        @Index(name = "idx_finding_fingerprint", columnList = "fingerprint")
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
    /** CSV of every endpoint that shares this finding's root cause (a shared DTO field), when more than one. Null/blank
     *  for the usual single-endpoint finding. Persisted so a live re-render keeps the cross-endpoint collapse. */
    @Column(length = 2000)
    private String affectedEndpoints;
    /** Spec-side root-cause locus ("<specSchemaName>#<field>") of a schema-field finding, when known. Persisted so a
     *  live re-render can reproduce the spec-locus cross-endpoint collapse. Null for non-schema-field findings. */
    @Column(length = 500)
    private String specLocus;
    private String specSource;

    @Column(length = 2000)
    private String summary;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String explanation;

    private String codeFile;
    private Integer codeStartLine;
    private Integer codeEndLine;

    /** Not persisted: a Bitbucket deep link to this finding's code evidence, computed per-request from the scan's
     *  repo coordinates + the configured base URL. Null when not resolvable. Serialized to the dashboard. */
    @jakarta.persistence.Transient
    private String codeUrl;
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

    // The reconcile LLM flagged this deterministic finding as a likely false positive (excluded from the release gate
    // but still listed). Persisted so a live re-render reproduces the as-scanned view; severity is never altered.
    // @ColumnDefault so ddl-auto=update can ADD this NOT NULL column to a pre-existing `finding` table — SQLite
    // rejects adding a NOT NULL column without a non-null default (portable: "false" is valid on SQLite + Postgres).
    @ColumnDefault("false")
    private boolean aiDisputed;
    @Column(length = 1000)
    private String aiDisputeReason;

    // A human's severity override (nullable) — wins over the engine `severity` at the gate and is carried forward
    // across re-scans by fingerprint (with the reviewedBy/At audit above). Nullable, so ddl-auto can ADD it freely.
    // The engine `severity` above stays as the deterministic "suggested" default the UI shows next to the override.
    @Column(length = 20)
    private String userSeverity;
}
