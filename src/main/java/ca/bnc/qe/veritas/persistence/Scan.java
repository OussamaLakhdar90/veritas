package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/** One contract-validation run against a service + its spec(s). Findings hang off it. */
@Entity
@Table(name = "scan")
@Getter
@Setter
public class Scan extends AuditableEntity {

    private String serviceName;
    private String appId;
    private String repoSlug;
    private String gitRef;

    @Column(length = 1000)
    private String specSources;   // comma-separated spec ids/locations validated in this scan

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RunStatus status;

    /** Live progress stage for the dashboard stepper: QUEUED|CLONING|EXTRACTING|DIFFING|RECONCILING|REPORTING|DONE. */
    @Column(length = 20)
    private String stage;

    /** Live sub-step detail of the current stage, shown under the active step (e.g. "Generating the corrected
     *  spec…", "Reviewing findings — batch 2 of 3"). Cleared on each stage transition. */
    @Column(length = 300)
    private String stageDetail;

    /** The Copilot model resolved for this scan's AI (reconcile) step — surfaced live in the UI. */
    @Column(length = 100)
    private String model;

    /** The stage the scan was in WHEN it failed (the live `stage` is overwritten with FAILED), so the UI can show
     *  where it actually failed instead of always blaming the last step. Null unless status == FAILED. */
    @Column(length = 20)
    private String failedStage;

    /**
     * Optimistic-lock version. The worker thread issues many progress saves while the stale-timeout
     * {@code ScanReconciler} may concurrently mark a long-running scan FAILED. Without this, a stale worker save
     * would silently overwrite the reconciler's FAILED (resurrecting a dead scan); with it, the conflicting save
     * throws {@code OptimisticLockingFailureException} and is handled gracefully. Scoped to Scan only.
     * Nullable {@code Long} (not primitive) so SQLite can ALTER-ADD the column to an existing table; Hibernate
     * initialises it to 0 on insert.
     */
    @Version
    private Long version;

    private int totalFindings;
    private String owner;
    private Instant startedAt;
    private Instant finishedAt;

    // LLM cost for the reconcile step of this scan.
    private double totalPremiumRequests;
    private double totalEstCostUsd;

    // LLM self-review of the validation (confidence 0–100 + blind spots).
    private Double confidence;
    @Column(length = 2000)
    private String blindSpots;

    /** Deterministic count of per-scan coverage gaps (files that didn't parse, DTOs that didn't resolve). */
    private Integer coverageGaps;

    /** Contract Fidelity Score 0–100 (deterministic) + the previous scan's score for trend. */
    private Integer fidelityScore;
    private Integer previousFidelityScore;

    @Column(length = 2000)
    private String errorMessage;

    /** EN→FR translation map (JSON) captured at scan time, so a live re-render stays bilingual. */
    @jakarta.persistence.Lob
    @Column(columnDefinition = "TEXT")
    private String translationsJson;
}
