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
        Instant startedAt,
        /** MTTR anchors: createdAt = detection→remediation start (the honest clock — startedAt is a machine-phase
         *  clock the reconciler resets mid-flight); finishedAt set on DONE. */
        Instant createdAt,
        Instant finishedAt,
        String watchId,
        List<SnykFixStepView> steps) {
}
