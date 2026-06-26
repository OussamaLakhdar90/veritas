package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A <b>test condition</b> — "a testable aspect identified during test analysis" (ISTQB CTFL §1.4: test analysis
 * answers <i>what to test?</i>). This is the work product that sits between the Test Strategy (ISTQB Test Manager)
 * and the Test Cases (ISTQB Test Analyst): each condition traces a basis item and a strategy risk forward to the
 * cases that cover it (basis ↔ condition ↔ case), and carries the automation-candidacy decision
 * (MANUAL | AUTOMATED | CANDIDATE) so the auto/manual split is made per condition, not per ad-hoc case.
 */
@Entity
@Table(name = "test_condition", indexes = {
        @Index(name = "idx_cond_service", columnList = "serviceName"),
        @Index(name = "idx_cond_strategy", columnList = "testStrategyId")
})
@Getter
@Setter
public class TestCondition extends AuditableEntity {

    private String serviceName;

    /** Human-facing id within the analysis list (e.g. {@code TCD-001}) — the "ID" column of the §3.1 table. */
    private String conditionRef;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    /** The basis item this condition traces to (requirement key / endpoint / story id) — the "Source" column. */
    private String sourceBasisItem;

    private String priority;               // P1..P4, risk-aligned
    private String riskRef;                // risk id from the strategy's risk register
    private String qualityCharacteristic;  // ISO 25010 characteristic
    private String technique;              // suggested ISTQB design technique (filled in at test design)

    /** The automation-candidacy decision for this condition. */
    private String automation;             // MANUAL | AUTOMATED | CANDIDATE

    @Lob
    @Column(columnDefinition = "TEXT")
    private String automationRationale;    // why auto/manual (risk × repeatability × stability)

    private String status;                 // PROPOSED | APPROVED | REJECTED

    /** Traceability spine: the approved strategy this analysis derived from (conditions are risk-based). */
    private String testStrategyId;

    private Double confidence;             // analysis self-review confidence 0–100
    private String owner;
    private double estCostUsd;
}
