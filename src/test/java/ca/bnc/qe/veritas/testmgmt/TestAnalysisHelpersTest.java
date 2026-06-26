package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import org.junit.jupiter.api.Test;

/** Pure-logic guards for the test-analysis helpers: strategy selection + automation normalization. */
class TestAnalysisHelpersTest {

    private TestStrategy strategy(String status, int version) {
        TestStrategy s = new TestStrategy();
        s.setStatus(status);
        s.setVersion(version);
        return s;
    }

    @Test
    void selectStrategyPrefersTheApprovedOneOverNewerDrafts() {
        // createdAt-desc order: a newer DRAFT in front, the APPROVED baseline behind it.
        TestStrategy draft = strategy("DRAFT", 3);
        TestStrategy approved = strategy("APPROVED", 2);
        TestStrategy picked = TestAnalysisService.selectStrategy("svc", List.of(draft, approved));
        assertThat(picked).isSameAs(approved);
    }

    @Test
    void selectStrategyFallsBackToTheMostRecentWhenNoneApproved() {
        TestStrategy newest = strategy("DRAFT", 2);
        TestStrategy older = strategy("DRAFT", 1);
        assertThat(TestAnalysisService.selectStrategy("svc", List.of(newest, older))).isSameAs(newest);
    }

    @Test
    void normalizeAutomationMapsToTheCanonicalSet() {
        assertThat(TestAnalysisService.normalizeAutomation("automated")).isEqualTo("AUTOMATED");
        assertThat(TestAnalysisService.normalizeAutomation("AUTO")).isEqualTo("AUTOMATED");
        assertThat(TestAnalysisService.normalizeAutomation("Manual")).isEqualTo("MANUAL");
        assertThat(TestAnalysisService.normalizeAutomation("candidate")).isEqualTo("CANDIDATE");
        assertThat(TestAnalysisService.normalizeAutomation("something-else")).isEqualTo("CANDIDATE");
        assertThat(TestAnalysisService.normalizeAutomation(null)).isEqualTo("CANDIDATE");
    }
}
