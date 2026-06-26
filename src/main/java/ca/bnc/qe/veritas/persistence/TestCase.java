package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A generated/managed test case. Lifecycle: PROPOSED → APPROVED → CREATED_IN_XRAY → ATTACHED → IMPLEMENTED. */
@Entity
@Table(name = "test_case")
@Getter
@Setter
public class TestCase extends AuditableEntity {

    private String serviceName;
    private String title;
    private String technique;
    private String priority;
    private String type;
    private String level;          // test level: Unit | Integration | System | Acceptance
    private String automation;     // MANUAL | AUTOMATED | CANDIDATE

    @Lob
    @Column(columnDefinition = "TEXT")
    private String stepsJson;

    private String status;
    private String xrayKey;
    private String linkedRequirement;
    private String testConditionId;    // the test condition this case was designed from (basis ↔ condition ↔ case)
    private String dedupFingerprint;   // stable fingerprint for idempotent create / dedup
    private String approvedBy;
    private java.time.Instant approvedAt;

    /** Why this technique/case (ISTQB justification) — part of the structured deliverable. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String rationale;

    private Double confidence;   // batch self-review confidence 0–100

    private String owner;
    private double estCostUsd;
}
