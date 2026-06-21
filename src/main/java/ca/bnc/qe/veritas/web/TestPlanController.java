package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.persistence.CoverageItem;
import ca.bnc.qe.veritas.persistence.CoverageItemRepository;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import ca.bnc.qe.veritas.report.CoverageReportRenderer;
import ca.bnc.qe.veritas.testmgmt.ReleaseTestPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-plan REST: read the structured deliverable + RTM, trigger a release test plan (gated outward writes),
 * and serve the RTM report (html|pdf). {@code {service}} is the service name until a Service entity lands.
 */
@RestController
@RequestMapping("/api/v1")
public class TestPlanController {

    private final TestPlanRepository plans;
    private final CoverageItemRepository coverage;
    private final ReleaseTestPlanService releaseTestPlanService;
    private final CoverageReportRenderer coverageReportRenderer;

    public TestPlanController(TestPlanRepository plans, CoverageItemRepository coverage,
                             ReleaseTestPlanService releaseTestPlanService,
                             CoverageReportRenderer coverageReportRenderer) {
        this.plans = plans;
        this.coverage = coverage;
        this.releaseTestPlanService = releaseTestPlanService;
        this.coverageReportRenderer = coverageReportRenderer;
    }

    @GetMapping("/test-plans")
    public List<TestPlan> list() {
        return plans.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/test-plans/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return plans.findById(id)
                .map(plan -> ResponseEntity.ok(Map.<String, Object>of(
                        "plan", plan,
                        "coverage", coverage.findByTestPlanId(id))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Trigger a release test plan: synthesize → reconcile coverage → (gated) create gap tests + attach.
     * Returns 202 with the coverage summary; outward Xray/Jira writes only happen when the gate is approved
     * (createGaps + server-mode gate). The RTM is always produced for review.
     */
    @PostMapping("/services/{service}/release-test-plans")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReleaseTestPlanService.CoverageSummary createReleasePlan(@PathVariable String service,
                                                                    @RequestBody ReleasePlanRequest req) {
        return releaseTestPlanService.generate(service, req.fixVersion(), req.issuesJql(), req.testsJql(),
                req.projectKey(), req.createGaps(), req.owner() == null ? "api" : req.owner());
    }

    /** RTM report for a plan, rendered live as HTML (default) or PDF. */
    @GetMapping("/test-plans/{id}/report")
    public ResponseEntity<?> report(@PathVariable String id,
                                    @RequestParam(defaultValue = "html") String format) {
        TestPlan plan = plans.findById(id).orElse(null);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }
        List<CoverageItem> items = coverage.findByTestPlanId(id);
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = coverageReportRenderer.renderPdf(plan, items);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", "inline; filename=\"rtm-" + id + ".pdf\"")
                    .body(pdf);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(coverageReportRenderer.renderHtml(plan, items));
    }

    public record ReleasePlanRequest(String fixVersion, String issuesJql, String testsJql, String projectKey,
                                     boolean createGaps, String owner) {}
}
