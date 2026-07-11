package ca.bnc.qe.veritas.snyk.fix;

import ca.bnc.qe.veritas.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One repo's PR in a fix train — the persisted form of a {@link CascadeStep}, tracked through
 * PLANNED → BRANCH_PUSHED → PR_OPEN → MERGED (or MANUAL when the planner couldn't resolve it). {@code prOpenedBy}
 * records whether Veritas or the user opened the PR (the breaking-change path lets the user do it).
 */
@Entity
@Table(name = "snyk_fix_step", indexes = @Index(name = "idx_snyk_step_train", columnList = "trainId"))
@Getter
@Setter
public class SnykFixStep extends AuditableEntity {

    private String trainId;
    private int stepOrder;

    private String bitbucketProject;
    private String repoSlug;
    private String branch;
    private String pomPath;
    private String moduleLabel;     // BOM | core | api | web | consumer:app7576

    @Column(length = 2000)
    private String diffPreview;
    private String newModuleVersion;
    @Column(length = 1000)
    private String reviewersJson;

    @Column(length = 40)
    private String commitSha;       // the sha of the commit that carries this step's change (set once pushed)

    private String prUrl;
    @Column(length = 20)
    private String prOpenedBy;      // VERITAS | USER

    @Column(length = 20)
    private String status;          // PLANNED | RUNNING | BRANCH_PUSHED | PR_OPEN | MERGED | MANUAL | FAILED
    @Column(length = 300)
    private String stageDetail;     // the live line while this module is active (e.g. "Pushing core…")
    private boolean manual;
    @Column(length = 500)
    private String reason;
}
