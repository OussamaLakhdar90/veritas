package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.bnc.qe.veritas.persistence.CoverageItemRepository;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.report.CoverageReportRenderer;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test for the release-test-plan trigger (the #1 audit blocker): POST returns 202 + summary. */
@WebMvcTest(TestPlanController.class)
class TestPlanControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private TestPlanRepository plans;
    @MockBean private CoverageItemRepository coverage;
    @MockBean private ReleaseTestPlanService releaseTestPlanService;
    @MockBean private CoverageReportRenderer coverageReportRenderer;

    @Test
    void triggeringAReleasePlanReturns202WithSummary() throws Exception {
        when(releaseTestPlanService.generate(eq("ciam-policies"), eq("8.2"), any(), any(), eq("CIAM"), anyBoolean(), any()))
                .thenReturn(new ReleaseTestPlanService.CoverageSummary(
                        "plan-1", 5, 3, 2, 0, 1, 0.0, "out/rtm-plan-1.html", 84.0, 2));

        mvc.perform(post("/api/v1/services/ciam-policies/release-test-plans")
                        .contentType("application/json")
                        .content("{\"fixVersion\":\"8.2\",\"projectKey\":\"CIAM\",\"createGaps\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.planId").value("plan-1"))
                .andExpect(jsonPath("$.gaps").value(2))
                .andExpect(jsonPath("$.confidence").value(84.0));
    }
}
