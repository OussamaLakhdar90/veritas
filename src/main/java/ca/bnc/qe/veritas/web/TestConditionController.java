package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.report.TestConditionListRenderer;
import ca.bnc.qe.veritas.testmgmt.TestAnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-condition REST (ISTQB test analysis): list/inspect conditions, generate them from a basis (derives from the
 * approved strategy), edit per-condition automation/status/priority (the auto-vs-manual split), and render the
 * auditable Test Condition List document.
 */
@RestController
@RequestMapping("/api/v1")
public class TestConditionController {

    private static final Set<String> ALLOWED_AUTOMATION = Set.of("MANUAL", "AUTOMATED", "CANDIDATE");
    private static final Set<String> ALLOWED_STATUS = Set.of("PROPOSED", "APPROVED", "REJECTED");

    private final TestConditionRepository repository;
    private final TestStrategyRepository strategyRepository;
    private final TestAnalysisService service;
    private final TestConditionListRenderer renderer;

    public TestConditionController(TestConditionRepository repository, TestStrategyRepository strategyRepository,
                                   TestAnalysisService service, TestConditionListRenderer renderer) {
        this.repository = repository;
        this.strategyRepository = strategyRepository;
        this.service = service;
        this.renderer = renderer;
    }

    @GetMapping("/services/{service}/test-conditions")
    public List<TestCondition> list(@PathVariable String service) {
        return repository.findByServiceNameOrderByCreatedAtDesc(service);
    }

    /** Conditions derived from a strategy (ordered by list id) — backs the Test Condition List view. */
    @GetMapping("/strategies/{id}/test-conditions")
    public List<TestCondition> forStrategy(@PathVariable String id) {
        return repository.findByTestStrategyIdOrderByConditionRefAsc(id);
    }

    @GetMapping("/test-conditions/{id}")
    public ResponseEntity<TestCondition> get(@PathVariable String id) {
        return repository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/services/{service}/test-conditions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<TestCondition> generate(@PathVariable("service") String svc, @RequestBody GenerateRequest req) {
        return service.analyze(svc, req.basis(), req.source() == null ? "CODE" : req.source(),
                req.owner() == null ? "api" : req.owner());
    }

    /** Per-condition edit — the auto/manual decision lives here (plus status / priority overrides). */
    @PatchMapping("/test-conditions/{id}")
    public ResponseEntity<TestCondition> patch(@PathVariable String id, @RequestBody ConditionPatch patch) {
        TestCondition tc = repository.findById(id).orElse(null);
        if (tc == null) {
            return ResponseEntity.notFound().build();
        }
        if (patch.automation() != null) {
            String a = patch.automation().toUpperCase(Locale.ROOT);
            if (!ALLOWED_AUTOMATION.contains(a)) {
                throw new IllegalArgumentException("Unknown automation '" + patch.automation()
                        + "'. Allowed: " + ALLOWED_AUTOMATION);
            }
            tc.setAutomation(a);
        }
        if (patch.status() != null) {
            String s = patch.status().toUpperCase(Locale.ROOT);
            if (!ALLOWED_STATUS.contains(s)) {
                throw new IllegalArgumentException("Unknown status '" + patch.status()
                        + "'. Allowed: " + ALLOWED_STATUS);
            }
            tc.setStatus(s);
        }
        if (patch.priority() != null) {
            tc.setPriority(patch.priority());
        }
        return ResponseEntity.ok(repository.save(tc));
    }

    /**
     * Tag-driven routing for a strategy's conditions: which feed automation (implement-tests) vs manual design
     * (create-test-cases). Makes the per-condition automation candidacy actionable as two work-lists.
     */
    @GetMapping("/strategies/{id}/test-conditions/routing")
    public AutomationRouting routing(@PathVariable String id) {
        List<TestCondition> conds = repository.findByTestStrategyIdOrderByConditionRefAsc(id);
        List<String> automated = new java.util.ArrayList<>();
        List<String> manual = new java.util.ArrayList<>();
        List<String> candidate = new java.util.ArrayList<>();
        for (TestCondition c : conds) {
            String a = c.getAutomation() == null ? "CANDIDATE" : c.getAutomation().toUpperCase(Locale.ROOT);
            switch (a) {
                case "AUTOMATED" -> automated.add(c.getConditionRef());
                case "MANUAL" -> manual.add(c.getConditionRef());
                default -> candidate.add(c.getConditionRef());
            }
        }
        return new AutomationRouting(automated, manual, candidate);
    }

    /** The auditable Test Condition List document for a strategy's conditions. */
    @GetMapping(value = "/strategies/{id}/test-conditions/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable String id) {
        return strategyRepository.findById(id)
                .map(s -> ResponseEntity.ok(
                        renderer.renderHtml(s, repository.findByTestStrategyIdOrderByConditionRefAsc(id))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record GenerateRequest(String basis, String source, String owner) {}

    public record AutomationRouting(List<String> automated, List<String> manual, List<String> candidate) {}

    public record ConditionPatch(String automation, String status, String priority) {}
}
