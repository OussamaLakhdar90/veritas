package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.persistence.CoverageItem;
import ca.bnc.qe.veritas.persistence.CoverageItemRepository;
import ca.bnc.qe.veritas.persistence.TestPlan;
import ca.bnc.qe.veritas.persistence.TestPlanRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API the dashboard consumes for the structured test-plan deliverable: the plan (with its structured
 * {@code deliverableJson} — exec summary, risk register, approach, exit criteria, self-review) plus its RTM
 * coverage rows.
 */
@RestController
@RequestMapping("/api/v1")
public class TestPlanController {

    private final TestPlanRepository plans;
    private final CoverageItemRepository coverage;

    public TestPlanController(TestPlanRepository plans, CoverageItemRepository coverage) {
        this.plans = plans;
        this.coverage = coverage;
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
}
