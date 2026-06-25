package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService.CoverageSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * Chunk-and-merge for large releases (blind spot #8): a tiny per-batch token budget forces the release
 * issues to be synthesized in several batches and merged deterministically — proving no requirement is
 * elided to fit one prompt and the merged deliverable is coherent.
 */
@SpringBootTest
@TestPropertySource(properties = "veritas.llm.batch-input-tokens=10")
class ReleaseTestPlanBatchingTest {

    @Autowired
    private ReleaseTestPlanService service;

    @MockBean
    private JiraClient jira;

    @MockBean
    private XrayClient xray;

    @MockBean
    private SecretProvider secrets;

    @Autowired
    private TestPlanRepository plans;

    @Autowired
    private TestStrategyRepository strategies;

    @BeforeEach
    void seed() {
        when(secrets.get("JIRA_API_TOKEN")).thenReturn(Optional.of("pat-123"));
        if (strategies.findByServiceNameOrderByCreatedAtDesc("batch-svc").isEmpty()) {
            TestStrategy s = new TestStrategy();
            s.setServiceName("batch-svc");
            s.setContentMarkdown("risk register (seed)");
            strategies.save(s);
        }
    }

    @Test
    void largeReleaseIsSynthesizedInBatchesThenMerged() {
        // 3 testable issues + a tiny batch budget → 3 batches. The mock returns the same plan per batch, so a
        // correct merge must DEDUP back to the single plan's cases/risks (not triple them).
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                JiraIssue.basic("CIAM-1", "Create policy", null),
                JiraIssue.basic("CIAM-2", "Get policy", null),
                JiraIssue.basic("CIAM-3", "Update policy", null)));
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        CoverageSummary summary = service.generate("batch-svc", "8.2", null, "project = CIAM", "CIAM", false, "tester");

        TestPlan plan = plans.findById(summary.planId()).orElseThrow();
        // The merge ran and is transparent about it (recorded as a blind spot in the deliverable).
        assertThat(plan.getDeliverableJson()).contains("batches over 3 release issues");
        // Required cases + risks are merged AND deduped (mock returns 2 cases / 2 risks each batch → still 2 each).
        assertThat(summary.total()).isEqualTo(2);
        assertThat(plan.getRiskCount()).isEqualTo(2);
        // Confidence is the most conservative across batches (identical here → unchanged).
        assertThat(summary.confidence()).isEqualTo(84.0);
    }
}
