package ca.bnc.qe.veritas.snyk.fix;

import java.time.Instant;
import java.util.List;

/** Dashboard view of a fix train: the issue being fixed, the Jira + verdict, the reactor result, and every PR. */
public record SnykFixTrainView(
        String id,
        String coordinate,
        String oldVersion,
        String fixedIn,
        String severity,
        String appIds,
        String jiraKey,
        /** The shared bulk story this train belongs to (null for a single fix) — the frontend groups a batch by it. */
        String storyKey,
        /** The ticket's live workflow status (In Progress → In Review → Done), for the dashboard chip. */
        String jiraStatus,
        String status,
        String stageDetail,
        /** Why a FAILED train failed + the stage it failed at — surfaced so a failure isn't a bare red badge. */
        String errorMessage,
        String failedStage,
        /** The cascade step (BOM/core/api/web/app) to blame when one module broke the build, so the UI can pinpoint it. */
        Integer failedStepOrder,
        boolean breaking,
        Boolean reactorPassed,
        String reactorFailingLabel,
        String reactorOutputTail,
        BreakingVerdict verdict,
        /** The advisory AI read of what the fix actually changed (null when never run / offline). */
        FixDiffVerdict fixDiff,
        Instant startedAt,
        /** MTTR anchors: createdAt = detection→remediation start (the honest clock — startedAt is a machine-phase
         *  clock the reconciler resets mid-flight); finishedAt set on DONE. */
        Instant createdAt,
        Instant finishedAt,
        String watchId,
        List<SnykFixStepView> steps) {
}
