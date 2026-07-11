package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.vcs.GitHost;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The clean-vs-breaking decision + the human-in-the-loop PR actions (who opens each PR, merge → Done). */
class SnykFixActionsTest {

    private final GitHost gitHost = mock(GitHost.class);
    private final SnykFixJiraService jira = mock(SnykFixJiraService.class);
    private final FrameworkProperties fw = new FrameworkProperties();
    private final SnykFixTrainRepository trains = mock(SnykFixTrainRepository.class);
    private final SnykFixStepRepository steps = mock(SnykFixStepRepository.class);
    private final SnykFixActions actions =
            new SnykFixActions(gitHost, jira, fw, trains, steps, new ObjectMapper());

    @BeforeEach
    void stubSaves() {
        when(trains.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(steps.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private SnykFixTrain train() {
        SnykFixTrain t = new SnykFixTrain();
        t.setId("t1");
        t.setJiraKey("CIAM-1");
        return t;
    }

    private SnykFixStep step(int order, boolean manual, String status) {
        SnykFixStep s = new SnykFixStep();
        s.setTrainId("t1");
        s.setStepOrder(order);
        s.setManual(manual);
        s.setStatus(status);
        s.setRepoSlug("lsist-test-framework-bom");
        s.setBitbucketProject("APP7488");
        s.setBranch("veritas/snyk-fix-abc");
        s.setReviewersJson("[\"alice\"]");
        return s;
    }

    @Test
    void cleanOpensThePrTrainAndMovesJiraToInReview() {
        when(gitHost.openPullRequest(any(GitHost.PullRequestSpec.class))).thenReturn("http://host/pr/1");
        SnykFixTrain t = train();
        t.setReactorPassed(true);
        t.setBreaking(false);
        SnykFixStep s = step(1, false, SnykFixStatus.BRANCH_PUSHED);

        actions.decide(t, List.of(s));

        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.PR_OPEN);
        assertThat(s.getStatus()).isEqualTo(SnykFixStatus.STEP_PR_OPEN);
        assertThat(s.getPrUrl()).isEqualTo("http://host/pr/1");
        assertThat(s.getPrOpenedBy()).isEqualTo(SnykFixStatus.BY_VERITAS);
        verify(jira).transitionTo(eq("CIAM-1"), eq(SnykFixJiraService.Phase.IN_REVIEW));
    }

    @Test
    void inconclusiveVerificationHoldsWithAnHonestReasonNotAsBreaking() {
        // The reactor couldn't verify the app because ITS OWN build/test config failed (not the upgrade). The train
        // must HOLD (never auto-open an unverified fix) with an "inconclusive" reason — never mislabeled breaking.
        SnykFixTrain t = train();
        t.setReactorPassed(false);
        t.setReactorInconclusive(true);
        t.setBreaking(false);                       // the LLM did NOT flag the upgrade breaking
        t.setReactorFailingLabel("consumer:APP7576");

        actions.decide(t, List.of(step(1, false, SnykFixStatus.BRANCH_PUSHED)));

        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.AWAITING_MANUAL_FIX);
        assertThat(t.getStageDetail()).contains("inconclusive");
        assertThat(t.isBreaking()).isFalse();       // NOT re-marked as a breaking change
        verify(gitHost, never()).openPullRequest(any(GitHost.PullRequestSpec.class));  // held, not auto-opened
        verify(jira, never()).transitionTo(any(), any());                              // Jira not moved to In Review
    }

    @Test
    void prTitleAndBodyCarryTheJiraKeyAndSummaryForLinkage() {
        ArgumentCaptor<GitHost.PullRequestSpec> cap = ArgumentCaptor.forClass(GitHost.PullRequestSpec.class);
        when(gitHost.openPullRequest(cap.capture())).thenReturn("http://host/pr/1");
        SnykFixTrain t = train();                        // jiraKey = CIAM-1
        t.setJiraSummary("Dependency security fixes");
        t.setReactorPassed(true);
        t.setBreaking(false);

        actions.decide(t, List.of(step(1, false, SnykFixStatus.BRANCH_PUSHED)));

        GitHost.PullRequestSpec spec = cap.getValue();
        assertThat(spec.title()).startsWith("CIAM-1 ");                                       // key prefixes the title
        assertThat(spec.description()).contains("Jira: CIAM-1 — Dependency security fixes");  // key + name in the body
    }

    @Test
    void openStepPrMarksTheModuleRunningBeforeItsPrOpens() {
        when(gitHost.openPullRequest(any(GitHost.PullRequestSpec.class))).thenReturn("http://host/pr/1");
        List<String> statusSequence = new ArrayList<>();
        when(steps.save(any())).thenAnswer(inv -> {
            statusSequence.add(((SnykFixStep) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });
        SnykFixTrain t = train();
        t.setReactorPassed(true);
        t.setBreaking(false);

        actions.decide(t, List.of(step(1, false, SnykFixStatus.BRANCH_PUSHED)));

        // The module is shown RUNNING (spinner) BEFORE it flips to PR_OPEN — the live stepper advance.
        assertThat(statusSequence).containsSubsequence(SnykFixStatus.RUNNING, SnykFixStatus.STEP_PR_OPEN);
    }

    @Test
    void breakingDetailNamesTheModuleThatFailedTheBuild() {
        SnykFixTrain t = train();
        t.setReactorPassed(false);            // the reactor failed → breaking / action-needed
        t.setReactorFailingLabel("core");

        actions.decide(t, List.of(step(1, false, SnykFixStatus.BRANCH_PUSHED)));

        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.AWAITING_MANUAL_FIX);
        assertThat(t.getStageDetail()).contains("Action needed").contains("core");   // pinpoints the failed étape
    }

    @Test
    void breakingHoldsThePrsAndAwaitsTheUser() {
        SnykFixTrain t = train();
        t.setReactorPassed(false);   // reactor failed → breaking, even if the LLM said non-breaking
        SnykFixStep s = step(1, false, SnykFixStatus.BRANCH_PUSHED);

        actions.decide(t, List.of(s));

        assertThat(t.isBreaking()).isTrue();
        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.AWAITING_MANUAL_FIX);
        verify(gitHost, never()).openPullRequest(any(GitHost.PullRequestSpec.class));
        verify(jira, never()).transitionTo(any(), eq(SnykFixJiraService.Phase.IN_REVIEW));
    }

    @Test
    void recordUserPrMarksTheStepAndAdvancesWhenAllOpen() {
        SnykFixTrain t = train();
        t.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
        SnykFixStep s = step(1, false, SnykFixStatus.BRANCH_PUSHED);
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(s));

        actions.recordUserPr("t1", 1, "http://host/user-pr");

        assertThat(s.getStatus()).isEqualTo(SnykFixStatus.STEP_PR_OPEN);
        assertThat(s.getPrOpenedBy()).isEqualTo(SnykFixStatus.BY_USER);
        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.PR_OPEN);
        verify(jira).transitionTo(eq("CIAM-1"), eq(SnykFixJiraService.Phase.IN_REVIEW));
    }

    @Test
    void recordUserPrRejectsANonHttpUrl() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actions.recordUserPr("t1", 1, "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(trains, never()).findById(any());   // rejected before touching the store
    }

    @Test
    void openHeldPrsOpensPrsForABreakingTrain() {
        when(gitHost.openPullRequest(any(GitHost.PullRequestSpec.class))).thenReturn("http://host/pr/9");
        SnykFixTrain t = train();
        t.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
        SnykFixStep s = step(1, false, SnykFixStatus.BRANCH_PUSHED);
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(s));

        actions.openHeldPrs("t1");

        assertThat(s.getPrOpenedBy()).isEqualTo(SnykFixStatus.BY_VERITAS);
        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.PR_OPEN);
        verify(jira).transitionTo(eq("CIAM-1"), eq(SnykFixJiraService.Phase.IN_REVIEW));
    }

    @Test
    void lifecycleActionsRejectAWrongState() {
        SnykFixTrain planning = train();
        planning.setStatus(SnykFixStatus.PLANNING);
        when(trains.findById("t1")).thenReturn(Optional.of(planning));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of());
        // markMerged needs PR_OPEN; openHeldPrs needs AWAITING_MANUAL_FIX — both reject a PLANNING train (409).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actions.markMerged("t1"))
                .isInstanceOf(ca.bnc.qe.veritas.skill.ConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actions.openHeldPrs("t1"))
                .isInstanceOf(ca.bnc.qe.veritas.skill.ConflictException.class);
    }

    @Test
    void decideSkipsAStepWhosePushFailedRatherThanMislabellingIt() {
        SnykFixTrain t = train();
        t.setReactorPassed(true);
        t.setBreaking(false);
        SnykFixStep failed = step(1, false, SnykFixStatus.STEP_FAILED);
        SnykFixStep ok = step(2, false, SnykFixStatus.BRANCH_PUSHED);
        when(gitHost.openPullRequest(any(GitHost.PullRequestSpec.class))).thenReturn("http://host/pr/2");

        actions.decide(t, List.of(failed, ok));

        // The failed-push step is left FAILED (not opened / not relabelled); only the pushed step gets a PR.
        assertThat(failed.getStatus()).isEqualTo(SnykFixStatus.STEP_FAILED);
        assertThat(ok.getStatus()).isEqualTo(SnykFixStatus.STEP_PR_OPEN);
        verify(gitHost, org.mockito.Mockito.times(1)).openPullRequest(any(GitHost.PullRequestSpec.class));
    }

    @Test
    void markMergedClosesTheTrainAndMovesJiraToDone() {
        SnykFixTrain t = train();
        t.setStatus(SnykFixStatus.PR_OPEN);   // mark-merged is only valid once the PRs are open
        SnykFixStep s = step(1, false, SnykFixStatus.STEP_PR_OPEN);
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(s));

        actions.markMerged("t1");

        assertThat(s.getStatus()).isEqualTo(SnykFixStatus.MERGED);
        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.DONE);
        assertThat(t.getFinishedAt()).isNotNull();
        verify(jira).transitionTo(eq("CIAM-1"), eq(SnykFixJiraService.Phase.DONE));
    }

    @Test
    void decideHoldsTheTrainAsManualWhenEveryPrOpenFailsRatherThanClaimingPrOpen() {
        when(gitHost.openPullRequest(any(GitHost.PullRequestSpec.class)))
                .thenThrow(new RuntimeException("bitbucket unreachable"));
        SnykFixTrain t = train();
        t.setReactorPassed(true);
        t.setBreaking(false);
        SnykFixStep s = step(1, false, SnykFixStatus.BRANCH_PUSHED);

        actions.decide(t, List.of(s));

        // No PR opened → don't claim PR_OPEN and don't move Jira; the branch is pushed, so hold for the user.
        assertThat(s.getStatus()).isEqualTo(SnykFixStatus.BRANCH_PUSHED);
        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.AWAITING_MANUAL_FIX);
        verify(jira, never()).transitionTo(any(), eq(SnykFixJiraService.Phase.IN_REVIEW));
    }

    @Test
    void decideHoldsAsManualWhenEveryPushFailedRatherThanClaimingPrOpenWithZeroPrs() {
        SnykFixTrain t = train();
        t.setReactorPassed(true);
        t.setBreaking(false);
        // Both steps failed to push earlier (no branch exists) → openAll skips them, so the actionable set is EMPTY.
        // Guards the vacuous allMatch: an empty stream is trivially "all open" → this used to falsely claim PR_OPEN.
        SnykFixStep a = step(1, false, SnykFixStatus.STEP_FAILED);
        SnykFixStep b = step(2, false, SnykFixStatus.STEP_FAILED);

        actions.decide(t, List.of(a, b));

        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.AWAITING_MANUAL_FIX);
        assertThat(t.getStageDetail()).contains("No PR was opened");   // honest: nothing was pushed, so nothing opened
        verify(gitHost, never()).openPullRequest(any(GitHost.PullRequestSpec.class));
        verify(jira, never()).transitionTo(any(), eq(SnykFixJiraService.Phase.IN_REVIEW));
    }

    @Test
    void markMergedMergesOnlyTheStepsWhosePrsWereActuallyOpen() {
        SnykFixTrain t = train();
        t.setStatus(SnykFixStatus.PR_OPEN);
        SnykFixStep opened = step(1, false, SnykFixStatus.STEP_PR_OPEN);
        SnykFixStep pushed = step(2, false, SnykFixStatus.BRANCH_PUSHED);   // its PR never opened
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(opened, pushed));

        actions.markMerged("t1");

        assertThat(opened.getStatus()).isEqualTo(SnykFixStatus.MERGED);
        assertThat(pushed.getStatus()).isEqualTo(SnykFixStatus.BRANCH_PUSHED);   // NOT falsely marked merged
        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.DONE);
    }

    @Test
    void recordUserPrDoesNotOverwriteAStepWhosePrIsAlreadyOpen() {
        SnykFixTrain t = train();
        t.setStatus(SnykFixStatus.PR_OPEN);
        SnykFixStep s = step(1, false, SnykFixStatus.STEP_PR_OPEN);
        s.setPrUrl("http://host/veritas-pr");
        s.setPrOpenedBy(SnykFixStatus.BY_VERITAS);
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(s));

        actions.recordUserPr("t1", 1, "http://host/user-pr");

        // The already-open Veritas PR is preserved, not clobbered with the user's URL/opener.
        assertThat(s.getPrUrl()).isEqualTo("http://host/veritas-pr");
        assertThat(s.getPrOpenedBy()).isEqualTo(SnykFixStatus.BY_VERITAS);
    }

    @Test
    void recordUserPrCanCompleteATrainThatHasAPushFailedStep() {
        SnykFixTrain t = train();
        t.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
        SnykFixStep failed = step(1, false, SnykFixStatus.STEP_FAILED);   // no branch, can't open a PR
        SnykFixStep pushed = step(2, false, SnykFixStatus.BRANCH_PUSHED);
        when(trains.findById("t1")).thenReturn(Optional.of(t));
        when(steps.findByTrainIdOrderByStepOrder("t1")).thenReturn(List.of(failed, pushed));

        actions.recordUserPr("t1", 2, "http://host/user-pr");

        // The STEP_FAILED step no longer blocks completion — recording the openable step advances the train.
        assertThat(pushed.getStatus()).isEqualTo(SnykFixStatus.STEP_PR_OPEN);
        assertThat(t.getStatus()).isEqualTo(SnykFixStatus.PR_OPEN);
    }
}
