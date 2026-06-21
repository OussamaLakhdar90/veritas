package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One row of the Requirements Traceability Matrix: a required case matched to an existing test, or a gap. */
@Entity
@Table(name = "coverage_item", indexes = @Index(name = "idx_cov_plan", columnList = "testPlanId"))
@Getter
@Setter
public class CoverageItem extends AuditableEntity {

    private String testPlanId;
    private String requirementKey;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String requiredCaseRef;

    private String dimension;       // REQUIREMENT | RISK | LEVEL | TYPE | TECHNIQUE
    private String matchStatus;     // MATCHED | GAP | CREATED | SKIPPED_DUP | FAILED | ORPHAN | DEAD | NON_TESTABLE
    private String matchedTestKey;
    private String confidence;

    /** Free-text context — e.g. the error message when creation FAILED (partial-failure-safe per-case result). */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String note;
}
