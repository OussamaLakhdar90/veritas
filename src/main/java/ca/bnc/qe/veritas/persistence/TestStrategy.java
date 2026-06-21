package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A generated ISTQB (Test Manager) test strategy for a service. */
@Entity
@Table(name = "test_strategy")
@Getter
@Setter
public class TestStrategy extends AuditableEntity {

    private String serviceName;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String contentMarkdown;

    /** Full structured deliverable (risk register, approach, exit criteria, self-review) as JSON. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String deliverableJson;

    private Double confidence;   // self-review confidence 0–100

    private String status;       // DRAFT | APPROVED
    private String source;       // CODE | JIRA_CONFLUENCE
    private String owner;
    private double estCostUsd;
}
