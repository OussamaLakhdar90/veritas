package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.jira.JiraVersion;
import ca.bnc.qe.veritas.persistence.CoverageItem;
import ca.bnc.qe.veritas.persistence.CoverageItemRepository;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayTest;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService.CoverageSummary;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class ReleaseTestPlanServiceTest {

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
    private CoverageItemRepository coverage;

    @BeforeEach
    void seedToken() {
        when(secrets.get("JIRA_API_TOKEN")).thenReturn(Optional.of("pat-123"));   // satisfies write-scope preflight
    }

    @Test
    void reconcilesCoverageWithMatchedAndGap() {
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                new JiraIssue("CIAM-1", "Create policy", null),
                new JiraIssue("CIAM-2", "Get policy", null)));
        when(xray.getTestsByJql(any())).thenReturn(List.of(
                new XrayTest("CIAM-T1", "2001", "Validate create policy", "Manual", List.of()),
                new XrayTest("CIAM-T9", "2009", "Legacy smoke test", "Manual", List.of())));

        CoverageSummary summary = service.generate("ciam-policies", "8.2", null, "project = CIAM", "CIAM", false, "tester");

        assertThat(summary.total()).isEqualTo(2);   // mock plan returns 2 required cases
        assertThat(summary.matched()).isEqualTo(1); // "Validate create policy" matches the existing test
        assertThat(summary.gaps()).isEqualTo(1);    // "Validate get policy" is a gap
        assertThat(summary.created()).isZero();     // createGaps = false
        assertThat(summary.orphans()).isEqualTo(1); // "Legacy smoke test" matches no required case
        assertThat(summary.reportPath()).isNotNull();
        assertThat(summary.planId()).isNotBlank();

        // Structured, consultant-grade deliverable is persisted (self-review + risk register).
        assertThat(summary.confidence()).isEqualTo(84.0);
        assertThat(summary.risks()).isEqualTo(2);
        TestPlan plan = plans.findById(summary.planId()).orElseThrow();
        assertThat(plan.getDeliverableJson()).contains("riskRegister").contains("exitCriteria").contains("selfReview");
        assertThat(plan.getConfidence()).isEqualTo(84.0);
        assertThat(plan.getRiskCount()).isEqualTo(2);
    }

    @Test
    void createsAndAttachesTestsToReleaseTestPlan() {
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                new JiraIssue("CIAM-1", "Create policy", null),
                new JiraIssue("CIAM-2", "Get policy", null)));
        when(xray.getTestsByJql(any())).thenReturn(List.of(
                new XrayTest("CIAM-T1", "2001", "Validate create policy", "Manual", List.of())));
        when(xray.createTest(any())).thenReturn("CIAM-NEW");
        when(jira.createIssue(any())).thenReturn("CIAM-TP1");

        CoverageSummary summary = service.generate("ciam-policies", "8.2", null, "project = CIAM", "CIAM", true, "tester");

        verify(xray).addTestsToTestPlan(eq("CIAM-TP1"), any());   // matched + created tests attached
        TestPlan plan = plans.findById(summary.planId()).orElseThrow();
        assertThat(plan.getXrayTestPlanKey()).isEqualTo("CIAM-TP1");
        assertThat(summary.created()).isEqualTo(1);
    }

    @Test
    void reRunCreatesNothingNew() {
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                new JiraIssue("CIAM-1", "Create policy", null),
                new JiraIssue("CIAM-2", "Get policy", null)));
        when(jira.createIssue(any())).thenReturn("CIAM-TP1");
        when(xray.createTest(any())).thenReturn("CIAM-NEW1", "CIAM-NEW2");

        // Run 1: no existing tests → both required cases are gaps → created.
        when(xray.getTestsByJql(any())).thenReturn(List.of());
        CoverageSummary first = service.generate("svc", "8.2", null, "project = CIAM", "CIAM", true, "tester");
        assertThat(first.created()).isEqualTo(2);

        // Run 2: the two created tests now exist (titles == required-case titles) → matched, nothing created.
        when(xray.getTestsByJql(any())).thenReturn(List.of(
                new XrayTest("CIAM-NEW1", "2001", "Validate create policy", "Manual", List.of()),
                new XrayTest("CIAM-NEW2", "2002", "Validate get policy", "Manual", List.of())));
        CoverageSummary second = service.generate("svc", "8.2", null, "project = CIAM", "CIAM", true, "tester");
        assertThat(second.created()).isZero();
        assertThat(second.matched()).isEqualTo(2);

        verify(xray, times(2)).createTest(any());   // exactly the two from run 1, none from the idempotent re-run
    }

    @Test
    void rejectsReleaseNotAmongProjectVersions() {
        when(jira.listVersions(any())).thenReturn(List.of(new JiraVersion("1", "7.0", true, false)));
        assertThatThrownBy(() -> service.generate("svc", "8.2", null, null, "CIAM", false, "tester"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("not found among project");
    }

    @Test
    void recordsNonTestableAndMultiDimensionCoverage() {
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                new JiraIssue("CIAM-1", "Create policy", null),
                new JiraIssue("CIAM-9", "[SPIKE] research auth options", null)));
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        CoverageSummary summary = service.generate("svc", "8.2", null, "project = CIAM", "CIAM", false, "tester");
        List<CoverageItem> cov = coverage.findByTestPlanId(summary.planId());

        assertThat(cov).anyMatch(c -> "NON_TESTABLE".equals(c.getMatchStatus()));   // B2: spike excluded + recorded
        assertThat(cov).anyMatch(c -> "TECHNIQUE".equals(c.getDimension()));        // B6: multi-dim RTM
    }
}
