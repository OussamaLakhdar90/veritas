package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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
}
