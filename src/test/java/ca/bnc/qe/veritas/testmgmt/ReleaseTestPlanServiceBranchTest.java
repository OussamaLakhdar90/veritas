package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.jira.JiraVersion;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayTest;
import ca.bnc.qe.veritas.persistence.CoverageItem;
import ca.bnc.qe.veritas.persistence.CoverageItemRepository;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService.CoverageSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Branch coverage for {@link ReleaseTestPlanService} — exercises the error paths, optional-argument branches,
 * and edge cases NOT covered by ReleaseTestPlanServiceTest / ReleaseTestPlanBatchingTest:
 * fixVersion/createGaps preflight validation, the explicit-JQL and explicit-tests-JQL branches, the
 * empty-tests (no orphans) path, the deliverable-JSON strategy basis, write-scope enforcement after the gate,
 * the non-fatal link-to-requirement failure, and the empty-attach (every create failed) path.
 */
@SpringBootTest
class ReleaseTestPlanServiceBranchTest {

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

    @Autowired
    private TestStrategyRepository strategies;

    @BeforeEach
    void seed() {
        when(secrets.get("JIRA_API_TOKEN")).thenReturn(Optional.of("pat-123"));   // satisfies write-scope preflight
        // The services under test all need a pre-existing strategy (hard dependency). Seed a markdown-only one and
        // a separate deliverable-JSON one so both strategy-basis branches (lines 119-120) are exercised.
        seedMarkdownStrategy("branch-svc");
        seedMarkdownStrategy("explicit-jql-svc");
        seedMarkdownStrategy("no-tests-svc");
        seedMarkdownStrategy("empty-versions-svc");
        seedMarkdownStrategy("matching-version-svc");
        seedMarkdownStrategy("link-fail-svc");
        seedMarkdownStrategy("all-fail-svc");
        seedMarkdownStrategy("noscope-svc");
        if (strategies.findByServiceNameOrderByCreatedAtDesc("deliverable-svc").isEmpty()) {
            TestStrategy s = new TestStrategy();
            s.setServiceName("deliverable-svc");
            s.setContentMarkdown("md fallback (should be ignored)");
            // Non-blank deliverableJson → strategyBasis takes the JSON branch, not the markdown branch.
            s.setDeliverableJson("{\"riskRegister\":[{\"id\":\"R1\"}]}");
            strategies.save(s);
        }
    }

    private void seedMarkdownStrategy(String svc) {
        if (strategies.findByServiceNameOrderByCreatedAtDesc(svc).isEmpty()) {
            TestStrategy s = new TestStrategy();
            s.setServiceName(svc);
            s.setContentMarkdown("risk register (seed)");
            strategies.save(s);
        }
    }

    private void twoIssues() {
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                JiraIssue.basic("CIAM-1", "Create policy", null),
                JiraIssue.basic("CIAM-2", "Get policy", null)));
    }

    // ---- preflight: release scope (lines 76-84) -------------------------------------------------------

    @Test
    void rejectsWhenBothFixVersionAndIssuesJqlAreBlank() {
        // blank(fixVersion) && blank(issuesJql) → "Provide a release fixVersion ..." (preflight before the try).
        assertThatThrownBy(() ->
                service.generate("branch-svc", "  ", null, "project = X", "CIAM", false, "tester"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Provide a release fixVersion");
    }

    @Test
    void rejectsCreateGapsWithoutProjectKey() {
        // createGaps && blank(projectKey) → "Creating gap tests requires a project key ...".
        assertThatThrownBy(() ->
                service.generate("branch-svc", "8.2", null, null, null, true, "tester"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("requires a project key");
    }

    // ---- version validation (lines 102-108) -----------------------------------------------------------

    @Test
    void proceedsWhenProjectHasNoDiscoverableVersions() {
        // projectKey != null && issuesJql == null, but listVersions is empty → the !versions.isEmpty() guard is
        // false, so validation is skipped and the run proceeds normally.
        when(jira.listVersions(eq("CIAM"))).thenReturn(List.of());
        twoIssues();
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        CoverageSummary summary = service.generate("empty-versions-svc", "9.9", null, null, "CIAM", false, "tester");

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.gaps()).isEqualTo(2);   // no tests-query (testsJql null, projectKey present → "project = CIAM")
        verify(jira).listVersions("CIAM");
    }

    @Test
    void proceedsWhenReleaseMatchesAProjectVersionCaseInsensitively() {
        // A matching version (case-insensitive) → noneMatch is false → no PreconditionException.
        when(jira.listVersions(eq("CIAM"))).thenReturn(List.of(
                new JiraVersion("10", "REL-8.2", false, false),
                new JiraVersion("11", "rel-8.2", false, false)));
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                JiraIssue.basic("CIAM-1", "Create policy", null),
                JiraIssue.basic("CIAM-2", "Get policy", null)));
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        CoverageSummary summary = service.generate("matching-version-svc", "Rel-8.2", null, null, "CIAM", false, "tester");

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.planId()).isNotBlank();
    }

    // ---- explicit JQL branches (lines 102, 122, 192) --------------------------------------------------

    @Test
    void explicitIssuesJqlSkipsVersionValidationAndIsPassedThrough() {
        // issuesJql != null → version validation is skipped (the projectKey != null && issuesJql == null guard is
        // false), and the explicit JQL is forwarded to jira.search verbatim.
        when(jira.search(eq("labels = release-target"), any(), anyInt())).thenReturn(List.of(
                JiraIssue.basic("CIAM-1", "Create policy", null),
                JiraIssue.basic("CIAM-2", "Get policy", null)));
        when(xray.getTestsByJql(eq("project = CIAM"))).thenReturn(List.of(
                new XrayTest("CIAM-T1", "2001", "Validate create policy", "Manual", List.of())));

        CoverageSummary summary = service.generate(
                "explicit-jql-svc", "8.2", "labels = release-target", null, "CIAM", false, "tester");

        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.gaps()).isEqualTo(1);
        verify(jira).search(eq("labels = release-target"), any(), anyInt());
        verify(jira, never()).listVersions(any());   // explicit JQL means no version probe
    }

    @Test
    void noTestsQueryYieldsNoExistingTestsAndNoOrphans() {
        // testsJql == null && projectKey == null && issuesJql provided → testsQuery == null → existing = [],
        // xray.getTestsByJql is never called, and there are no orphans.
        when(jira.search(eq("filter = rel"), any(), anyInt())).thenReturn(List.of(
                JiraIssue.basic("CIAM-1", "Create policy", null),
                JiraIssue.basic("CIAM-2", "Get policy", null)));

        CoverageSummary summary = service.generate(
                "no-tests-svc", "8.2", "filter = rel", null, null, false, "tester");

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.gaps()).isEqualTo(2);    // nothing to match against → both gaps
        assertThat(summary.orphans()).isZero();     // no existing tests → no orphans
        verify(xray, never()).getTestsByJql(any());
    }

    @Test
    void explicitTestsJqlIsUsedForTheCoverageQuery() {
        // testsJql != null → that query (not "project = ...") is used to fetch existing tests.
        twoIssues();
        when(xray.getTestsByJql(eq("project = OTHER AND type = Test"))).thenReturn(List.of(
                new XrayTest("OTH-T1", "3001", "Validate get policy", "Manual", List.of())));

        CoverageSummary summary = service.generate(
                "explicit-jql-svc", "8.2", "labels = x", "project = OTHER AND type = Test", "CIAM", false, "tester");

        assertThat(summary.matched()).isEqualTo(1);   // "Validate get policy" matches
        verify(xray).getTestsByJql("project = OTHER AND type = Test");
    }

    // ---- strategy basis branch (lines 119-120) --------------------------------------------------------

    @Test
    void usesDeliverableJsonAsStrategyBasisWhenPresent() {
        // strategy.getDeliverableJson() non-blank → strategyBasis takes the JSON branch (not contentMarkdown).
        // We can't observe the prompt directly, but the run must still succeed end-to-end on that branch.
        twoIssues();
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        CoverageSummary summary = service.generate("deliverable-svc", "8.2", null, "project = CIAM", "CIAM", false, "tester");

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.planId()).isNotBlank();
    }

    // ---- write-scope enforcement after the gate (lines 223-227, wrapped by 338-340) -------------------

    @Test
    void missingWriteTokenAfterGateFailsAsWrappedIllegalState() {
        // createGaps + projectKey → gate auto-approves → createApproved true → requireXrayWriteScope() runs.
        // With NO token of any kind present, it throws a PreconditionException INSIDE the try → wrapped as
        // IllegalStateException("release-test-plan failed: ...").
        when(secrets.get(any())).thenReturn(Optional.empty());   // override the seeded JIRA_API_TOKEN
        twoIssues();
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        assertThatThrownBy(() ->
                service.generate("noscope-svc", "8.2", null, "project = CIAM", "CIAM", true, "tester"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release-test-plan failed")
                .hasMessageContaining("write");
        verify(xray, never()).createTest(any());   // never reached the create loop
    }

    // ---- link-to-requirement non-fatal failure (lines 263-270) ----------------------------------------

    @Test
    void linkTestToRequirementFailureIsNonFatalAndTestStillCreated() {
        // Both required cases are gaps → both created; linking the new test to its requirement throws, which must
        // be swallowed (warn) so the create still counts and the batch survives.
        twoIssues();
        when(xray.getTestsByJql(any())).thenReturn(List.of());
        when(xray.createTest(any())).thenReturn("CIAM-NEW1", "CIAM-NEW2");
        when(jira.createIssue(any())).thenReturn("CIAM-TP1");
        doThrow(new RuntimeException("link 500")).when(xray).linkTestToRequirement(any(), any());

        CoverageSummary summary = service.generate("link-fail-svc", "8.2", null, "project = CIAM", "CIAM", true, "tester");

        assertThat(summary.created()).isEqualTo(2);   // link failure did not abort either create
        List<CoverageItem> cov = coverage.findByTestPlanId(summary.planId());
        assertThat(cov).filteredOn(c -> "CREATED".equals(c.getMatchStatus())).hasSize(2);
        // The link was attempted for each created test that carried a requirementKey (CIAM-1, CIAM-2).
        verify(xray).linkTestToRequirement("CIAM-NEW1", "CIAM-1");
        verify(xray).linkTestToRequirement("CIAM-NEW2", "CIAM-2");
    }

    // ---- empty-attach path: every create failed (lines 315-333) ---------------------------------------

    @Test
    void everyCreateFailingYieldsEmptyAttachSoNoReleaseTestPlanIsCreated() {
        // All gaps, all creates throw → no CREATED, no matched → attachSet empty → the createIssue/attach block is
        // skipped entirely (createApproved is true but attach.isEmpty()).
        twoIssues();
        when(xray.getTestsByJql(any())).thenReturn(List.of());   // both required cases are gaps
        when(xray.createTest(any())).thenThrow(new RuntimeException("Xray down"));

        CoverageSummary summary = service.generate("all-fail-svc", "8.2", null, "project = CIAM", "CIAM", true, "tester");

        assertThat(summary.created()).isZero();
        assertThat(summary.gaps()).isEqualTo(2);
        TestPlan plan = plans.findById(summary.planId()).orElseThrow();
        assertThat(plan.getXrayTestPlanKey()).isNull();         // no release Test Plan was created
        verify(jira, never()).createIssue(any(JiraCreateRequest.class));
        verify(xray, never()).addTestsToTestPlan(any(), any());
        List<CoverageItem> cov = coverage.findByTestPlanId(summary.planId());
        assertThat(cov).filteredOn(c -> "FAILED".equals(c.getMatchStatus())).hasSize(2);   // both failures recorded
    }
}