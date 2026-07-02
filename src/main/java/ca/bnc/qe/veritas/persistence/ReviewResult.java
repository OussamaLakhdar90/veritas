package ca.bnc.qe.veritas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/** Result of reviewing a test case (ISTQB Test Analyst rubric): score, verdict, gaps, corrected steps. */
@Entity
@Table(name = "review_result")
@Getter
@Setter
public class ReviewResult extends AuditableEntity {

    private String targetType;     // XRAY_TEST
    private String targetKey;
    private double score;
    private String verdict;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String gapsJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String correctedJson;

    /** Full structured review (rubric + self-review) as JSON. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String deliverableJson;

    private Double confidence;   // self-review confidence 0–100

    // @ColumnDefault so ddl-auto=update can ADD this NOT NULL column to a pre-existing table (SQLite rejects a
    // NOT NULL column added without a non-null default).
    @ColumnDefault("false")
    private boolean applied;
    private String owner;
    private double estCostUsd;
}
