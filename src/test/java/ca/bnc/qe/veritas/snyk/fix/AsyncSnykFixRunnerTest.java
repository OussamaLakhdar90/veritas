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

    private final AsyncSnykFixRunner runner = new AsyncSnykFixRunner(
            mock(WorkspaceService.class), mock(CascadePlanner.class), mock(CascadeVerifier.class),
            mock(BreakingChangeService.class), mock(SnykFixJiraService.class), mock(SnykFixActions.class),
            mock(ReviewerSuggester.class), mock(GeneratedFileWriter.class), prPublisher,
            new FrameworkProperties(), trains, steps, new ObjectMapper());

    private SnykFixRequest request() {
        return new SnykFixRequest("w1", "i1", "com.x:y", "1.0", "2.0", "critical",
                List.of("APP7576"), null, "CIAM", "Task", List.of(), "alice", false);
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
    void branchAndCommitEmbedTheJiraKeyWhenKnown() {
        // The Jira key rides in the branch AND the commit so Bitbucket links both to the ticket's dev panel.
        assertThat(AsyncSnykFixRunner.branchName("abcdef123456", "CIAM-50"))
                .isEqualTo("veritas/CIAM-50/snyk-fix-abcdef12");
        assertThat(AsyncSnykFixRunner.commitMessage("CIAM-50", "BOM", "bump x 1->2"))
                .isEqualTo("CIAM-50 Snyk fix: BOM — bump x 1->2");
    }

    @Test
    void branchAndCommitFallBackCleanlyWithoutAValidJiraKey() {
        // No key → the trainId-only branch + the un-prefixed commit (the pre-change behaviour).
        assertThat(AsyncSnykFixRunner.branchName("abcdef123456", null)).isEqualTo("veritas/snyk-fix-abcdef12");
        assertThat(AsyncSnykFixRunner.commitMessage(null, "core", "bump y")).isEqualTo("Snyk fix: core — bump y");
        // A malformed value must never land in a git ref (it would break the branch) → fall back.
        assertThat(AsyncSnykFixRunner.branchName("abcdef123456", "not a key")).isEqualTo("veritas/snyk-fix-abcdef12");
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

        runner.pushBranches("t1", Map.of("P/bom-repo", Path.of(".")), "veritas/x", "CIAM-1");

        // The module is shown RUNNING (spinner) BEFORE it flips to BRANCH_PUSHED — the live stepper advance.
        assertThat(statusSequence).containsSubsequence(SnykFixStatus.RUNNING, SnykFixStatus.BRANCH_PUSHED);
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

        runner.pushBranches("t1", Map.of("P/bom-repo", Path.of(".")), "veritas/x", "CIAM-1");

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
                "t1", List.of(unclonable), Map.of(), "veritas/x", List.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(savedStatuses).contains(SnykFixStatus.STEP_FAILED);   // the step was marked before the abort
    }

    @Test
    void failureReasonFallsBackToTheClassNameWhenTheMessageIsNullOrBlank() {
        assertThat(AsyncSnykFixRunner.failureReason(new RuntimeException("boom"))).isEqualTo("boom");
        assertThat(AsyncSnykFixRunner.failureReason(new NullPointerException())).isEqualTo("NullPointerException");
        assertThat(AsyncSnykFixRunner.failureReason(new IllegalStateException("   "))).isEqualTo("IllegalStateException");
    }
}
