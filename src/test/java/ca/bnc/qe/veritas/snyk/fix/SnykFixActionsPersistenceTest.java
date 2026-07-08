package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.vcs.GitHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * End-to-end proof — real repositories, the real {@code @Version} entity, the real merge path — that the
 * breaking-change "Open the PRs" action completes to PR_OPEN instead of self-inflicting an optimistic-lock failure.
 * <p>
 * {@link SnykFixActions#openHeldPrs} loads the train once (detached) then saves it twice. On a {@code @Version}
 * entity, {@code save()} is a JPA merge that increments the version on a fresh managed copy and does NOT write it
 * back to the detached instance — so before the fix the second save merged a stale version and threw
 * "Row was updated or deleted by another transaction" with no concurrent writer. That is the same class of failure
 * the user hit on a single fix; the repository-mocking unit tests never exercised the merge path, so it went unseen.
 */
@SpringBootTest
class SnykFixActionsPersistenceTest {

    @Autowired private SnykFixActions actions;
    @Autowired private SnykFixTrainRepository trains;
    @Autowired private SnykFixStepRepository steps;
    @MockBean private GitHost gitHost;
    @MockBean private SnykFixJiraService jiraService;

    @Test
    void openHeldPrsReachesPrOpenWithoutAStaleVersionFailure() {
        when(gitHost.openPullRequest(any())).thenReturn("https://bitbucket.bnc/ciam/pr/1");

        SnykFixTrain train = new SnykFixTrain();
        train.setStatus(SnykFixStatus.AWAITING_MANUAL_FIX);
        train.setJiraKey("CIAM-1");
        train = trains.save(train);

        SnykFixStep step = new SnykFixStep();
        step.setTrainId(train.getId());
        step.setStepOrder(1);
        step.setModuleLabel("BOM");
        step.setBitbucketProject("APP7488");
        step.setRepoSlug("lsist-test-framework-bom");
        step.setBranch("veritas/CIAM-1/snyk-fix-abc");
        step.setStatus(SnykFixStatus.BRANCH_PUSHED);
        step.setManual(false);
        steps.save(step);

        // Two saves of the same detached @Version train no longer collide → the action completes to PR_OPEN.
        // Before the fix this threw OptimisticLockingFailureException on the second save.
        SnykFixTrain out = actions.openHeldPrs(train.getId());

        assertThat(out.getStatus()).isEqualTo(SnykFixStatus.PR_OPEN);
        assertThat(trains.findById(train.getId()).orElseThrow().getStatus()).isEqualTo(SnykFixStatus.PR_OPEN);
    }
}
