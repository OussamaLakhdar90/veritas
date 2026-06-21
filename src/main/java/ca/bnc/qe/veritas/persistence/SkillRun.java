package ca.bnc.qe.veritas.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One invocation of a skill (its lifecycle, inputs, and terminal status). */
@Entity
@Table(name = "skill_run")
@Getter
@Setter
public class SkillRun extends AuditableEntity {

    @Column(nullable = false)
    private String skillName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    private Instant startedAt;
    private Instant finishedAt;

    @Column(length = 4000)
    private String inputJson;

    @Column(length = 2000)
    private String errorMessage;

    // Rolled-up LLM cost across all steps of this run.
    private double totalPremiumRequests;
    private double totalEstCostUsd;
}
