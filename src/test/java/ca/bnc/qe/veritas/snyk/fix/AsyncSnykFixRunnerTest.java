package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.codegen.GeneratedFileWriter;
import ca.bnc.qe.veritas.codegen.PrPublisher;
import ca.bnc.qe.veritas.skill.NotFoundException;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The synchronous guards on the async fix runner: dedup on submit + the confirm-phase preconditions. */
class AsyncSnykFixRunnerTest {

    private final SnykFixTrainRepository trains = mock(SnykFixTrainRepository.class);
    private final SnykFixStepRepository steps = mock(SnykFixStepRepository.class);
    private final PrPublisher prPublisher = mock(PrPublisher.class);
    private final FixDiffValidator fixDiffValidator = mock(FixDiffValidator.class);
    private final FrameworkProperties fw = new FrameworkProperties();

    private final AsyncSnykFixRunner runner = new AsyncSnykFixRunner(
            mock(WorkspaceService.class), mock(CascadePlanner.class), mock(CascadeVerifier.class),
            mock(BreakingChangeService.class), fixDiffValidator,
            mock(SnykFixJiraService.class), mock(SnykFixActions.class), mock(ReviewerSuggester.class),
            mock(GeneratedFileWriter.class), prPublisher, fw, trains, steps, new ObjectMapper());

    private SnykFixRequest request() {
        return new SnykFixRequest("w1", "i1", "com.x:y", "1.0", "2.0", "critical",
                List.of("APP7576"), null, "CIAM", "Task", List.of(), "alice", false, null);
    }

    private SnykFixTrain train(String id, String status) {
        SnykFixTrain t = new SnykFixTrain();
        t.setId(id);
        t.setStatus(status);
        return t;
    }

    private static SnykFixStep stepOf(int order, String moduleLabel) {
        SnykFixStep s = new SnykFixStep();
        s.setStepOrder(order);
        s.setModuleLabel(moduleLabel);
        return s;
    }

    @Test
    void submitReusesAnInFlightTrainForTheSameWatchAndCoordinate() {
        when(trains.findByWatchIdAndCoordinate("w1", "com.x:y"))
                .thenReturn(List.of(train("existing", SnykFixStatus.VERIFYING)));

        String id = runner.submit(request());

        assertThat(id).isEqualTo("existing");
        verify(trains, never()).save(any());   // no duplicate train row created / no background run kicked off
    }

    @Test
    void submitStartsAFreshTrainAfterThePriorOneWasCancelled() {
        // A CANCELLED prior train is TERMINAL (not in NON_TERMINAL), so the dedup guard must NOT reuse it — the exact
        // "Snyk fix already in flight" unblock: after a cancel, the same fix can be relaunched.
        when(trains.findByWatchIdAndCoordinate("w1", "com.x:y"))
                .thenReturn(List.of(train("cancelled-1", SnykFixStatus.CANCELLED)));
        when(trains.save(any())).thenAnswer(inv -> {
            SnykFixTrain t = inv.getArgument(0);
            t.setId("new-1");
            return t;
        });

        String id = runner.submit(request());

        assertThat(id).isEqualTo("new-1");   // a fresh train, NOT the cancelled one
    }

    @Test
    void submitCarriesForwardThePriorCancelledTrainsJiraKeyOnRelaunch() {
        // Relaunch with no key supplied + a prior CANCELLED train that had a key → the new train reuses that key, so it
        // resolves to the SAME branch (branchName(key, project)) and accumulates instead of duplicating.
        SnykFixTrain prior = train("cancelled-1", SnykFixStatus.CANCELLED);
        prior.setJiraKey("CIAM-9");
        prior.setStartedAt(java.time.Instant.now());
        when(trains.findByWatchIdAndCoordinate("w1", "com.x:y")).thenReturn(List.of(prior));
        org.mockito.ArgumentCaptor<SnykFixTrain> saved = org.mockito.ArgumentCaptor.forClass(SnykFixTrain.class);
        when(trains.save(saved.capture())).thenAnswer(inv -> {
            SnykFixTrain t = inv.getArgument(0);
            t.setId("new-1");
            return t;
        });

        runner.submit(request());   // request() supplies jiraKey=null

        assertThat(saved.getValue().getJiraKey()).isEqualTo("CIAM-9");   // carried forward → same ticket + branch
    }

    @Test
    void confirmRejectsATrainThatIsNotAwaitingConfirmation() {
        when(trains.findById("t1")).thenReturn(Optional.of(train("t1", SnykFixStatus.PLANNING)));
        assertThatThrownBy(() -> runner.confirm("t1", Map.of(), Map.of()))
                .isInstanceOf(ca.bnc.qe.veritas.skill.ConflictException.class);
        verify(trains, never()).save(any());
    }

    @Test
    void confirmThrowsNotFoundForAnUnknownTrain() {
        when(trains.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> runner.confirm("missing", Map.of(), Map.of()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void branchIsAFeatureBranchPerProjectAndTheCommitCarriesTheKeyAndDependency() {
        // BNC "feature" type, off develop, per Bitbucket project (APP7488 -> app-7488) with the Jira key.
        assertThat(AsyncSnykFixRunner.branchName("LSIST-439", "APP7488"))
                .isEqualTo("feature/LSIST-439-snyk-fix-app-7488");
        assertThat(AsyncSnykFixRunner.branchName("LSIST-439", "APP7571"))
                .isEqualTo("feature/LSIST-439-snyk-fix-app-7571");
        // Commit: <KEY>: Bump <artifactId> to <fixedIn> (artifactId only, not the full coordinate).
        assertThat(AsyncSnykFixRunner.commitMessage("LSIST-439", "org.apache.commons:commons-lang3", "3.18.0"))
                .isEqualTo("LSIST-439: Bump commons-lang3 to 3.18.0");
    }

    @Test
    void branchAndCommitFallBackCleanlyWithoutAValidJiraKey() {
        // No key → key-less feature branch + un-prefixed commit; a malformed key never lands in a git ref.
        assertThat(AsyncSnykFixRunner.branchName(null, "APP7488")).isEqualTo("feature/snyk-fix-app-7488");
        assertThat(AsyncSnykFixRunner.branchName("not a key", "APP7571")).isEqualTo("feature/snyk-fix-app-7571");
        assertThat(AsyncSnykFixRunner.commitMessage(null, "com.x:y", "2.0")).isEqualTo("Bump y to 2.0");
    }

    @Test
    void commitSubjectIsTruncatedToStayUnderTheHookLimit() {
        String longArtifact = "a".repeat(90);
        String msg = AsyncSnykFixRunner.commitMessage("LSIST-439", "g:" + longArtifact, "1.0");
        assertThat(msg).hasSizeLessThanOrEqualTo(72).startsWith("LSIST-439: Bump ").endsWith("...");
    }

    @Test
    void recognisesTheOptimisticLockRaceSoAFinalisedTrainIsNotReMarkedWithACrypticError() {
        // The exact failure the user hit: a train finalized by the reconciler/restart/shutdown underneath the worker
        // throws "Row was updated or deleted by another transaction". That is a benign "someone else won" — the
        // runner must NOT re-report it as a fix failure. A real error (anything else) is not swallowed.
        assertThat(AsyncSnykFixRunner.isConcurrentFinalize(
                new org.springframework.dao.OptimisticLockingFailureException("Row was updated or deleted"))).isTrue();
        assertThat(AsyncSnykFixRunner.isConcurrentFinalize(
                new RuntimeException("wrapped", new org.springframework.dao.OptimisticLockingFailureException("stale"))))
                .isTrue();
        assertThat(AsyncSnykFixRunner.isConcurrentFinalize(new IllegalStateException("a genuine bug"))).isFalse();
        assertThat(AsyncSnykFixRunner.isConcurrentFinalize(null)).isFalse();
    }

    @Test
    void failedStepOrderMapsTheReactorFailingModuleToItsCascadeStep() {
        List<SnykFixStep> plan = List.of(stepOf(1, "BOM"), stepOf(2, "core"), stepOf(3, "consumer:APP7576"));
        assertThat(AsyncSnykFixRunner.failedStepOrder(plan, "core")).isEqualTo(2);
        assertThat(AsyncSnykFixRunner.failedStepOrder(plan, "APP7576")).isEqualTo(3);   // a consumer app-id
        assertThat(AsyncSnykFixRunner.failedStepOrder(plan, "web")).isNull();           // not part of this cascade
        assertThat(AsyncSnykFixRunner.failedStepOrder(plan, null)).isNull();
    }

    @Test
    void pushBranchesMarksEachModuleRunningBeforeItIsPushed() {
        SnykFixStep bom = stepOf(1, "BOM");
        bom.setBitbucketProject("P");
        bom.setRepoSlug("bom-repo");
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(bom));
        List<String> statusSequence = new ArrayList<>();
        when(steps.save(any())).thenAnswer(inv -> {
            statusSequence.add(((SnykFixStep) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });

        runner.pushBranches("t1", Map.of("P/bom-repo", Path.of(".")), "CIAM-1", "com.x:y", "2.0");

        // The module is shown RUNNING (spinner) BEFORE it flips to BRANCH_PUSHED — the live stepper advance.
        assertThat(statusSequence).containsSubsequence(SnykFixStatus.RUNNING, SnykFixStatus.BRANCH_PUSHED);
    }

    @Test
    void pushBranchesRecordsTheCommitShaAndAPersistentSuccessDetail() {
        // Part D visibility: on a successful push the step keeps the commit sha (for the chip) AND a persistent,
        // non-null success line naming the branch — so the stepper says exactly what was done, not a blank.
        SnykFixStep bom = stepOf(1, "BOM");
        bom.setBitbucketProject("P");
        bom.setRepoSlug("bom-repo");
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(bom));
        when(steps.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prPublisher.pushBranch(any(), any(), any(), any()))
                .thenReturn(new PrPublisher.PushOutcome("feature/CIAM-1-snyk-fix-p", "abc1234def5678901234", true));

        runner.pushBranches("t1", Map.of("P/bom-repo", Path.of(".")), "CIAM-1", "com.x:y", "2.0");

        assertThat(bom.getStatus()).isEqualTo(SnykFixStatus.BRANCH_PUSHED);
        assertThat(bom.getCommitSha()).isEqualTo("abc1234def5678901234");
        assertThat(bom.getStageDetail()).as("a persistent success line, not null")
                .contains("abc1234").contains("new branch");
    }

    @Test
    void pushBranchesMarksAStepFailedAndSummarisesWhenThePushIsRejected() {
        // A push rejected for want of write scope must STOP the step at STEP_FAILED with a reason — not silently
        // continue and later be mistaken for a shipped fix (fail-fast + the failure-summary log branch).
        SnykFixStep bom = stepOf(1, "BOM");
        bom.setBitbucketProject("P");
        bom.setRepoSlug("bom-repo");
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(bom));
        when(steps.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("not authorized")).when(prPublisher)
                .pushBranch(any(), any(), any(), any());

        runner.pushBranches("t1", Map.of("P/bom-repo", Path.of(".")), "CIAM-1", "com.x:y", "2.0");

        assertThat(bom.getStatus()).isEqualTo(SnykFixStatus.STEP_FAILED);
        assertThat(bom.getReason()).contains("push failed").contains("not authorized");
    }

    @Test
    void cloneRequiredThrowsAClearReasonWhenAFrameworkRepoCannotBeCloned() {
        // The workspace mock returns null (clone failed) → a REQUIRED framework repo must fail the train fast with a
        // credentials/connectivity hint, not silently drop the module and resurface as a misleading reactor failure.
        assertThatThrownBy(() -> runner.cloneRequired("APP7488", "lsist-bom", "BOM", new LinkedHashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOM")
                .hasMessageContaining("connectivity/credentials");
    }

    @Test
    void persistStepsFailsFastWhenAVersionEditCannotBeApplied() {
        // A non-manual step whose repo is not in the clones map can't have its pom edited → the step is marked
        // STEP_FAILED and the exception is rethrown to abort the train (no change-less "fix" reaches push/PR).
        CascadeStep unclonable = new CascadeStep(1, "P", "bom-repo", "veritas/x", "pom.xml", "BOM",
                List.of(), "2.0", "1.0 -> 2.0", false, null);
        List<String> savedStatuses = new ArrayList<>();
        when(steps.save(any())).thenAnswer(inv -> {
            savedStatuses.add(((SnykFixStep) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });

        assertThatThrownBy(() -> runner.persistSteps(
                "t1", List.of(unclonable), Map.of(), "veritas/x", List.of(), Map.of(), "com.x", "y", "2.0"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(savedStatuses).contains(SnykFixStatus.STEP_FAILED);   // the step was marked before the abort
    }

    @Test
    void assertVulnPinnedHardFailsWhenTheBomEditDidNotReachTheFixedVersion() {
        // The change-less-"fix" catch: the BOM step's edited pom still resolves the vuln coordinate BELOW fixedIn
        // (a no-op / wrong-target edit that only bumped the release version) → throw so the train FAILS, no push.
        String stillVulnerable = """
                <project><dependencyManagement><dependencies>
                    <dependency><groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId><version>2.14.0</version></dependency>
                </dependencies></dependencyManagement></project>
                """;
        assertThatThrownBy(() -> AsyncSnykFixRunner.assertVulnPinned("BOM", stillVulnerable,
                "com.fasterxml.jackson.core", "jackson-databind", "2.15.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not raise").hasMessageContaining("2.14.0");
    }

    @Test
    void assertVulnPinnedPassesForAFixedBomAndIsANoOpForNonBomSteps() {
        String fixed = """
                <project><dependencyManagement><dependencies>
                    <dependency><groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId><version>2.15.0</version></dependency>
                </dependencies></dependencyManagement></project>
                """;
        // BOM edited pom now resolves the coordinate to fixedIn → no throw.
        AsyncSnykFixRunner.assertVulnPinned("BOM", fixed, "com.fasterxml.jackson.core", "jackson-databind", "2.15.0");
        // A non-BOM step never pins the coordinate → the gate is a no-op even on a pom that doesn't manage it.
        AsyncSnykFixRunner.assertVulnPinned("core", "<project/>", "com.fasterxml.jackson.core", "jackson-databind", "2.15.0");
    }

    @Test
    void recordFixDiffVerdictPersistsTheAdvisoryVerdictAndSwallowsAJudgeFailure() {
        when(trains.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SnykFixRequest req = request();
        // Success path: the advisory verdict is persisted as JSON on the train.
        when(fixDiffValidator.explain(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new FixDiffVerdict(true, true, "Raised jackson 3.1.1 -> 3.1.4", "reaches fixedIn"));
        SnykFixTrain t = train("t1", SnykFixStatus.VERIFYING);
        runner.recordFixDiffVerdict(t, "<old/>", Map.of(), req);
        assertThat(t.getFixDiffJson()).contains("fixesTheVuln").contains("Raised jackson");

        // Failure path: a judge failure is swallowed (advisory only) — never throws, the train is still returned.
        when(fixDiffValidator.explain(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("judge blew up"));
        SnykFixTrain t2 = train("t2", SnykFixStatus.VERIFYING);
        assertThat(runner.recordFixDiffVerdict(t2, "<old/>", Map.of(), req)).isNotNull();
    }

    @Test
    void terminalOutcomeIsAlreadyFixedOrFailedOnlyWhenNothingIsActionable() {
        SnykFixStep manual = stepOf(1, "BOM");
        manual.setManual(true);
        SnykFixStep actionable = stepOf(2, "core");   // manual defaults to false
        // An actionable (non-manual) step present → proceed (null).
        assertThat(AsyncSnykFixRunner.terminalOutcomeIfNothingActionable(List.of(manual, actionable), true)).isNull();
        // Nothing actionable + the BOM already ships the safe version → benign ALREADY_FIXED.
        assertThat(AsyncSnykFixRunner.terminalOutcomeIfNothingActionable(List.of(manual), true))
                .isEqualTo(SnykFixStatus.ALREADY_FIXED);
        // Nothing actionable + the BOM does NOT pin it → the fix genuinely couldn't be applied → FAILED.
        assertThat(AsyncSnykFixRunner.terminalOutcomeIfNothingActionable(List.of(manual), false))
                .isEqualTo(SnykFixStatus.FAILED);
    }

    @Test
    void failureReasonFallsBackToTheClassNameWhenTheMessageIsNullOrBlank() {
        assertThat(AsyncSnykFixRunner.failureReason(new RuntimeException("boom"))).isEqualTo("boom");
        assertThat(AsyncSnykFixRunner.failureReason(new NullPointerException())).isEqualTo("NullPointerException");
        assertThat(AsyncSnykFixRunner.failureReason(new IllegalStateException("   "))).isEqualTo("IllegalStateException");
    }
}
