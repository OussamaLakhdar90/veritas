package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A generated test plan (GLOBAL or RELEASE). Release plans carry a fixVersion + an Xray Test Plan key. */
@Entity
@Table(name = "test_plan")
@Getter
@Setter
public class TestPlan extends AuditableEntity {

    private String serviceName;
    private String kind;            // GLOBAL | RELEASE
    private String fixVersion;
    private String releaseVersionId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String contentMarkdown;

    /** The full structured deliverable (exec summary, risk register, approach, exit criteria, self-review) as JSON. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String deliverableJson;

    private Double confidence;      // self-review confidence 0–100
    private Integer riskCount;

    private String description;
    private String xrayTestPlanKey;
    private String strategyId;
    private String status;
    private String owner;
    private double estCostUsd;
}
