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
        boolean breaking,
        Boolean reactorPassed,
        String reactorFailingLabel,
        String reactorOutputTail,
        BreakingVerdict verdict,
        Instant startedAt,
        List<SnykFixStepView> steps) {
}
