package ca.bnc.qe.veritas.evolve;

import java.time.Instant;
import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * One Engine-Evolution classification proposal as it moves toward a merged engine change — the loop's unit of work,
 * mirroring the Snyk fix train. Created from the field votes ({@code PROPOSED}); optionally {@code CHALLENGED} by a
 * maintainer (a severity override + a required comment); {@code PR_OPEN} once the deterministic promotion PR is
 * pushed; {@code MERGED} when the human merges it. The AI's suggestion + rationale is kept alongside the
 * maintainer's final decision so the whole audit trail lands in the PR body.
 */
@Entity
@Table(name = "classification_train", indexes = {
        @Index(name = "idx_classification_train_status", columnList = "status"),
        @Index(name = "idx_classification_train_type", columnList = "findingType")
})
@Getter
@Setter
public class ClassificationTrain extends AuditableEntity {

    private String findingType;

    private String aiSuggestedSeverity;   // the AI's rubric-based suggestion (equals the consensus when AI was offline)
    private boolean aiSuggested;          // true = the LLM applied the rubric; false = defaulted to the field consensus
    @Lob
    @Column(columnDefinition = "TEXT")
    private String aiRationale;

    private String finalSeverity;         // the maintainer's decision — defaults to the AI suggestion until challenged
    @Column(length = 1000)
    private String maintainerComment;     // required when the maintainer overrides the suggestion

    private int voteCount;
    private int distinctServices;
    @Column(length = 1000)
    private String voteBreakdown;         // human-readable "MAJOR:4, CRITICAL:1" evidence for the PR body + UI

    private String appId;                 // the target repo (Veritas's own) recorded when the PR is opened
    private String repoSlug;

    @Column(length = 20)
    private String status;                // PROPOSED | CHALLENGED | PR_OPEN | MERGED

    private String gateId;
    private String prUrl;
    private String branch;

    private String owner;
    @Column(length = 2000)
    private String errorMessage;

    @Version
    private Long version;

    private Instant finishedAt;
}
