package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.vcs.GitHost;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
