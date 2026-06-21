package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.testmgmt.CreateTestCasesService;
import org.springframework.http.HttpStatus;
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
 * Test-case REST: list/inspect, generate (ISTQB Test Analyst), the per-row approve/edit/reject the RTM
 * workspace needs, and the gated push-to-Xray (create-approved). {@code {service}} is the service name.
 */
@RestController
@RequestMapping("/api/v1")
public class TestCaseController {

    private static final Set<String> ALLOWED_STATUS = Set.of(
            "PROPOSED", "APPROVED", "REJECTED", "CREATED_IN_XRAY", "ATTACHED", "IMPLEMENTED");

    private final TestCaseRepository repository;
    private final CreateTestCasesService service;

    public TestCaseController(TestCaseRepository repository, CreateTestCasesService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping("/services/{service}/test-cases")
    public List<TestCase> list(@PathVariable String service) {
        return repository.findByServiceNameOrderByCreatedAtDesc(service);
    }

    @GetMapping("/test-cases/{id}")
    public ResponseEntity<TestCase> get(@PathVariable String id) {
        return repository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Generate ISTQB test cases from a basis (code endpoints or stories). */
    @PostMapping("/services/{service}/test-cases")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<TestCase> generate(@PathVariable("service") String svc, @RequestBody GenerateRequest req) {
        return service.generate(svc, req.basis(), req.owner() == null ? "api" : req.owner());
    }

    /** Approve / edit / reject a proposed case (the RTM-workspace per-row action). */
    @PatchMapping("/test-cases/{id}")
    public ResponseEntity<TestCase> patch(@PathVariable String id, @RequestBody TestCasePatch patch) {
        TestCase tc = repository.findById(id).orElse(null);
        if (tc == null) {
            return ResponseEntity.notFound().build();
        }
        if (patch.status() != null) {
            String s = patch.status().toUpperCase(Locale.ROOT);
            if (!ALLOWED_STATUS.contains(s)) {
                throw new IllegalArgumentException("Unknown test-case status '" + patch.status()
                        + "'. Allowed: " + ALLOWED_STATUS);
            }
            tc.setStatus(s);
            if ("APPROVED".equals(s)) {
                tc.setApprovedBy(patch.actor() == null ? "api" : patch.actor());
                tc.setApprovedAt(java.time.Instant.now());
            }
        }
        if (patch.title() != null) {
            tc.setTitle(patch.title());
        }
        return ResponseEntity.ok(repository.save(tc));
    }

    /** Gated create-approved: push one approved case to Xray as a Test issue. */
    @PostMapping("/test-cases/{id}/push")
    public ResponseEntity<TestCase> push(@PathVariable String id, @RequestBody PushRequest req) {
        TestCase tc = repository.findById(id).orElse(null);
        if (tc == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.pushToXray(tc, req.projectKey(), req.owner() == null ? "api" : req.owner()));
    }

    public record GenerateRequest(String basis, String owner) {}

    public record TestCasePatch(String status, String title, String actor) {}

    public record PushRequest(String projectKey, String owner) {}
}
