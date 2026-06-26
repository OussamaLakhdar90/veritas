package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ReleaseTestPlanService#selectStrategy}: the release plan must derive from the
 * APPROVED, signed-off strategy — not merely the newest-created — so approve-then-revise can't silently base
 * the plan on an unapproved draft. Input is createdAt-desc (as the repository returns it).
 */
class ReleaseStrategySelectionTest {

    private TestStrategy strategy(String status, int version) {
        TestStrategy s = new TestStrategy();
        s.setServiceName("svc");
        s.setStatus(status);
        s.setVersion(version);
        s.setContentMarkdown(status + "-v" + version);
        return s;
    }

    @Test
    void prefersApprovedOverANewerDraft() {
        TestStrategy draftV2 = strategy("DRAFT", 2);       // most recently created → index 0
        TestStrategy approvedV1 = strategy("APPROVED", 1);
        // approve-v1 then revise-to-v2(DRAFT): the plan must still rest on the approved v1, not the newer draft.
        assertThat(ReleaseTestPlanService.selectStrategy("svc", List.of(draftV2, approvedV1)))
                .isSameAs(approvedV1);
    }

    @Test
    void picksTheLatestApprovedWhenSeveralAreApproved() {
        TestStrategy approvedV2 = strategy("APPROVED", 2);  // createdAt-desc → index 0 → latest approved
        TestStrategy approvedV1 = strategy("APPROVED", 1);
        assertThat(ReleaseTestPlanService.selectStrategy("svc", List.of(approvedV2, approvedV1)))
                .isSameAs(approvedV2);
    }

    @Test
    void fallsBackToMostRecentWhenNoneApproved() {
        TestStrategy draftV2 = strategy("DRAFT", 2);
        TestStrategy draftV1 = strategy("DRAFT", 1);
        assertThat(ReleaseTestPlanService.selectStrategy("svc", List.of(draftV2, draftV1)))
                .isSameAs(draftV2);   // newest-created (index 0)
    }

    @Test
    void approvedMatchIsCaseInsensitiveAndIgnoresInReviewAndNullStatus() {
        TestStrategy inReview = strategy("IN_REVIEW", 3);
        TestStrategy nullStatus = strategy(null, 2);
        TestStrategy approved = strategy("approved", 1);   // lowercase → still matches
        assertThat(ReleaseTestPlanService.selectStrategy("svc", List.of(inReview, nullStatus, approved)))
                .isSameAs(approved);
    }
}
