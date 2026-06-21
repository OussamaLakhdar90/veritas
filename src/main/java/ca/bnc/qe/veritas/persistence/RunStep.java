package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Per-step record of a skill run (kind, status, ordinal, duration) — makes runs auditable and resumable. */
@Entity
@Table(name = "run_step", indexes = @Index(name = "idx_run_step_run", columnList = "skillRunId"))
@Getter
@Setter
public class RunStep extends AuditableEntity {

    @Column(nullable = false)
    private String skillRunId;

    @Column(nullable = false)
    private String stepId;

    @Column(length = 20)
    private String kind;

    @Column(length = 20)
    private String status;

    private int ordinal;

    private long durationMs;

    // Cost accounting (populated for LLM steps; zero/null for deterministic + gate steps).
    private String model;
    private double premiumRequests;
    private long estTokensIn;
    private long estTokensOut;
    private double estCostUsd;
}
