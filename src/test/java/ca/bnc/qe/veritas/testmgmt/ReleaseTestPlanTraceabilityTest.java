package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService.CoverageSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Closed-world traceability gate on the release plan: a requirementKey that is not one of the fetched release issues
 * is dropped (never persisted or written outward as an Xray link), the plan is held back as NEEDS_REVIEW, and an
 * under-covered HIGH risk is surfaced as a blind spot without blocking.
 */
@SpringBootTest
class ReleaseTestPlanTraceabilityTest {

    @Autowired private ReleaseTestPlanService service;
    @MockBean private JiraClient jira;
    @MockBean private XrayClient xray;
    @MockBean private SecretProvider secrets;
    @Autowired private TestPlanRepository plans;
    @Autowired private ca.bnc.qe.veritas.persistence.TestStrategyRepository strategies;

    @BeforeEach
    void seed() {
        when(secrets.get("JIRA_API_TOKEN")).thenReturn(Optional.of("pat-123"));
        if (strategies.findByServiceNameOrderByCreatedAtDesc("trace-svc").isEmpty()) {
            ca.bnc.qe.veritas.persistence.TestStrategy s = new ca.bnc.qe.veritas.persistence.TestStrategy();
            s.setServiceName("trace-svc");
            s.setContentMarkdown("risk register (seed)");
            strategies.save(s);
        }
    }

    @Test
    void dropsAFabricatedRequirementKeyAndHoldsTheOutwardWrite() {
        // The mock plan cites CIAM-1 + CIAM-2; this release only contains CIAM-1, so CIAM-2 is fabricated for it.
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                JiraIssue.basic("CIAM-1", "Create policy", null)));
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        CoverageSummary summary = service.generate("trace-svc", "8.2", null, "project = CIAM", "CIAM", true, "tester");

        TestPlan plan = plans.findById(summary.planId()).orElseThrow();
        assertThat(plan.getStatus()).isEqualTo("NEEDS_REVIEW");               // a fabricated key was dropped
        assertThat(plan.getDeliverableJson()).contains("Dropped requirementKey 'CIAM-2'");
        assertThat(summary.created()).isZero();                              // outward write held back
        verify(xray, never()).createTest(any());                             // nothing fabricated written to Xray
        verify(xray, never()).linkTestToRequirement(any(), any());           // no fabricated coverage link
    }

    @Test
    void keepsVerifiedKeysAsDraftAndFlagsUnderCoveredHighRisksAsBlindSpots() {
        // Both cited keys are real release issues → nothing dropped → DRAFT. But each HIGH risk (R1, R2) has only one
        // traced case in the mock plan, so both are flagged as blind spots (advisory, non-blocking).
        when(jira.search(any(), any(), anyInt())).thenReturn(List.of(
                JiraIssue.basic("CIAM-1", "Create policy", null),
                JiraIssue.basic("CIAM-2", "Get policy", null)));
        when(xray.getTestsByJql(any())).thenReturn(List.of());

        CoverageSummary summary = service.generate("trace-svc", "8.2", null, "project = CIAM", "CIAM", false, "tester");

        TestPlan plan = plans.findById(summary.planId()).orElseThrow();
        assertThat(plan.getStatus()).isEqualTo("DRAFT");                     // verified traceability, healthy confidence
        assertThat(plan.getDeliverableJson())
                .contains("HIGH/VERY-HIGH risk 'R1'").contains("expects at least 2");
        assertThat(summary.total()).isEqualTo(2);                           // coverage counts unchanged
    }
}
