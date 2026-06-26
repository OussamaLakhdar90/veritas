package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.persistence.CoverageItem;
import ca.bnc.qe.veritas.persistence.CoverageItemRepository;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.report.CoverageReportRenderer;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Branch-focused web-layer test for {@link TestPlanController}: list, get (found / 404),
 * report html|pdf branches (incl. case-insensitive + 404), and the owner default branch.
 */
@WebMvcTest(TestPlanController.class)
class TestPlanControllerBranchTest {

    @Autowired private MockMvc mvc;
    @MockBean private TestPlanRepository plans;
    @MockBean private CoverageItemRepository coverage;
    @MockBean private ReleaseTestPlanService releaseTestPlanService;
    @MockBean private CoverageReportRenderer coverageReportRenderer;

    private static TestPlan plan(String id, String service, String kind) {
        TestPlan p = new TestPlan();
        p.setId(id);
        p.setServiceName(service);
        p.setKind(kind);
        p.setConfidence(91.5);
        return p;
    }

    private static CoverageItem item(String planId, String dimension, String matchStatus) {
        CoverageItem c = new CoverageItem();
        c.setTestPlanId(planId);
        c.setDimension(dimension);
        c.setMatchStatus(matchStatus);
        return c;
    }

    @Test
    void listReturnsPlansNewestFirst() throws Exception {
        when(plans.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(plan("p1", "ciam-policies", "RELEASE"),
                                    plan("p2", "ciam-auth", "GLOBAL")));

        mvc.perform(get("/api/v1/test-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("p1"))
                .andExpect(jsonPath("$[0].serviceName").value("ciam-policies"))
                .andExpect(jsonPath("$[0].kind").value("RELEASE"))
                .andExpect(jsonPath("$[1].id").value("p2"));
    }

    @Test
    void listReturnsEmptyArrayWhenNoPlans() throws Exception {
        when(plans.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        mvc.perform(get("/api/v1/test-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getReturnsPlanAndCoverageWhenFound() throws Exception {
        when(plans.findById("p1")).thenReturn(Optional.of(plan("p1", "ciam-policies", "RELEASE")));
        when(coverage.findByTestPlanId("p1"))
                .thenReturn(List.of(item("p1", "REQUIREMENT", "MATCHED"),
                                    item("p1", "RISK", "GAP")));

        mvc.perform(get("/api/v1/test-plans/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value("p1"))
                .andExpect(jsonPath("$.plan.serviceName").value("ciam-policies"))
                .andExpect(jsonPath("$.coverage.length()").value(2))
                .andExpect(jsonPath("$.coverage[0].dimension").value("REQUIREMENT"))
                .andExpect(jsonPath("$.coverage[1].matchStatus").value("GAP"));
    }

    @Test
    void getReturns404WhenPlanMissing() throws Exception {
        when(plans.findById("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/test-plans/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportRendersHtmlByDefault() throws Exception {
        TestPlan p = plan("p1", "ciam-policies", "RELEASE");
        when(plans.findById("p1")).thenReturn(Optional.of(p));
        List<CoverageItem> items = List.of(item("p1", "REQUIREMENT", "MATCHED"));
        when(coverage.findByTestPlanId("p1")).thenReturn(items);
        when(coverageReportRenderer.renderHtml(p, items)).thenReturn("<html>RTM</html>");

        mvc.perform(get("/api/v1/test-plans/p1/report"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<html>RTM</html>"));
    }

    @Test
    void reportRendersHtmlWhenFormatExplicitlyHtml() throws Exception {
        TestPlan p = plan("p2", "ciam-auth", "GLOBAL");
        when(plans.findById("p2")).thenReturn(Optional.of(p));
        when(coverage.findByTestPlanId("p2")).thenReturn(List.of());
        when(coverageReportRenderer.renderHtml(any(), any())).thenReturn("<html>explicit</html>");

        mvc.perform(get("/api/v1/test-plans/p2/report").param("format", "html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<html>explicit</html>"));
    }

    @Test
    void reportRendersHtmlForUnknownFormat() throws Exception {
        TestPlan p = plan("p3", "ciam-mfa", "RELEASE");
        when(plans.findById("p3")).thenReturn(Optional.of(p));
        when(coverage.findByTestPlanId("p3")).thenReturn(List.of());
        when(coverageReportRenderer.renderHtml(any(), any())).thenReturn("<html>fallback</html>");

        mvc.perform(get("/api/v1/test-plans/p3/report").param("format", "xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<html>fallback</html>"));
    }

    @Test
    void reportRendersPdfWhenFormatPdf() throws Exception {
        TestPlan p = plan("p1", "ciam-policies", "RELEASE");
        when(plans.findById("p1")).thenReturn(Optional.of(p));
        List<CoverageItem> items = List.of(item("p1", "RISK", "GAP"));
        when(coverage.findByTestPlanId("p1")).thenReturn(items);
        byte[] bytes = new byte[] {1, 2, 3, 4};
        when(coverageReportRenderer.renderPdf(p, items)).thenReturn(bytes);

        mvc.perform(get("/api/v1/test-plans/p1/report").param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"rtm-p1.pdf\""))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void reportPdfFormatIsCaseInsensitive() throws Exception {
        TestPlan p = plan("p9", "ciam-policies", "RELEASE");
        when(plans.findById("p9")).thenReturn(Optional.of(p));
        when(coverage.findByTestPlanId("p9")).thenReturn(List.of());
        byte[] bytes = new byte[] {9, 8, 7};
        when(coverageReportRenderer.renderPdf(any(), any())).thenReturn(bytes);

        mvc.perform(get("/api/v1/test-plans/p9/report").param("format", "PDF"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"rtm-p9.pdf\""))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void reportReturns404WhenPlanMissingHtml() throws Exception {
        when(plans.findById("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/test-plans/ghost/report"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportReturns404WhenPlanMissingEvenForPdf() throws Exception {
        when(plans.findById("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/test-plans/ghost/report").param("format", "pdf"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createReleasePlanDefaultsOwnerToApiWhenNull() throws Exception {
        when(releaseTestPlanService.generate(eq("ciam-policies"), eq("8.2"), any(), any(),
                        eq("CIAM"), anyBoolean(), eq("api")))
                .thenReturn(new ReleaseTestPlanService.CoverageSummary(
                        "plan-7", 5, 3, 2, 0, 1, 0.0, "out/rtm-plan-7.html", 84.0, 2));

        mvc.perform(post("/api/v1/services/ciam-policies/release-test-plans")
                        .contentType("application/json")
                        .content("{\"fixVersion\":\"8.2\",\"projectKey\":\"CIAM\",\"createGaps\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.planId").value("plan-7"))
                .andExpect(jsonPath("$.gaps").value(2));

        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
        verify(releaseTestPlanService).generate(eq("ciam-policies"), eq("8.2"), any(), any(),
                eq("CIAM"), anyBoolean(), ownerCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(ownerCaptor.getValue()).isEqualTo("api");
    }

    @Test
    void createReleasePlanUsesProvidedOwner() throws Exception {
        when(releaseTestPlanService.generate(eq("ciam-auth"), eq("9.0"), any(), any(),
                        eq("CIAM"), eq(true), eq("alice")))
                .thenReturn(new ReleaseTestPlanService.CoverageSummary(
                        "plan-8", 10, 7, 3, 1, 0, 1.5, "out/rtm-plan-8.html", 90.0, 4));

        mvc.perform(post("/api/v1/services/ciam-auth/release-test-plans")
                        .contentType("application/json")
                        .content("{\"fixVersion\":\"9.0\",\"projectKey\":\"CIAM\",\"createGaps\":true,\"owner\":\"alice\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.planId").value("plan-8"))
                .andExpect(jsonPath("$.matched").value(7))
                .andExpect(jsonPath("$.confidence").value(90.0));

        verify(releaseTestPlanService).generate(eq("ciam-auth"), eq("9.0"), any(), any(),
                eq("CIAM"), eq(true), eq("alice"));
    }
}
