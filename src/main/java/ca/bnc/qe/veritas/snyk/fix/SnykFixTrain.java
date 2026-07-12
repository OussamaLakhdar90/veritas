package ca.bnc.qe.veritas.snyk.fix;

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
 * One release-cascade fix: the whole PR train for a single fixable Snyk issue. Its {@link SnykFixStep}s are the
 * per-repo PRs. Carries the Jira ticket that drives it and the two verdicts (advisory LLM + the real reactor
 * build) whose combination decides clean (open the train) vs breaking (push-only, await the user).
 */
@Entity
@Table(name = "snyk_fix_train", indexes = {
        @Index(name = "idx_snyk_train_status", columnList = "status"),
        @Index(name = "idx_snyk_train_watch", columnList = "watchId")
})
@Getter
@Setter
public class SnykFixTrain extends AuditableEntity {

    private String watchId;
    private String issueId;

    private String coordinate;      // groupId:artifactId
    private String oldVersion;
    private String fixedIn;         // the safe version we upgrade to
    private String severity;        // critical | high | medium | low
    @Column(length = 500)
    private String appIds;          // comma-joined selected app-ids

    private String jiraKey;
    private String storyKey;        // the shared bulk story this train belongs to (null for a single-issue fix) — groups a batch
    @Column(length = 500)
    private String jiraSummary;     // the ticket's summary/title, surfaced in each PR body for reviewer context
    @Column(length = 100)
    private String jiraStatus;      // the ticket's live workflow status (In Progress → In Review → Done), for the UI chip
    private String jiraProject;
    private String jiraIssueType;   // kept so a paused (AWAITING_CONFIRM) train can rebuild its request on confirm

    @Column(length = 30)
    private String status;
    @Column(length = 300)
    private String stageDetail;
    @Column(length = 30)
    private String failedStage;
    private Integer failedStepOrder;   // the cascade step (BOM/core/api/web/app) that broke, when one module is to blame

    /** The decision: the LLM called it breaking OR the reactor build failed → push-only path. */
    private boolean breaking;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String verdictJson;     // the advisory BreakingVerdict
    @Lob
    @Column(columnDefinition = "TEXT")
    private String fixDiffJson;     // the advisory FixDiffVerdict — the AI's plain-language read of what the fix changed

    private Boolean reactorPassed;
    /** The reactor couldn't verify the app because ITS OWN build config/infra failed (not the upgrade) — held for
     *  manual review rather than reported as a breaking change. Distinct from a genuine reactor failure. */
    private Boolean reactorInconclusive;
    private String reactorFailingLabel;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String reactorOutputTail;

    private String owner;
    @Column(length = 2000)
    private String errorMessage;

    @Version
    private Long version;

    private Instant startedAt;
    private Instant finishedAt;
}
