package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.testmgmt.TestStrategyService;
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

/** Test-strategy REST (ISTQB Test Manager): list/inspect + generate the structured strategy deliverable. */
@RestController
@RequestMapping("/api/v1")
public class StrategyController {

    private final TestStrategyRepository repository;
    private final TestStrategyService service;
    private final ca.bnc.qe.veritas.report.StrategyRationaleRenderer rationaleRenderer;

    public StrategyController(TestStrategyRepository repository, TestStrategyService service,
                             ca.bnc.qe.veritas.report.StrategyRationaleRenderer rationaleRenderer) {
        this.repository = repository;
        this.service = service;
        this.rationaleRenderer = rationaleRenderer;
    }

    @GetMapping("/services/{service}/strategies")
    public List<TestStrategy> list(@PathVariable String service) {
        return repository.findByServiceNameOrderByCreatedAtDesc(service);
    }

    @GetMapping("/strategies/{id}")
    public ResponseEntity<TestStrategy> get(@PathVariable String id) {
        return repository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The "why" companion: a full ISTQB-grounded rationale document for the strategy (derived, can't drift). */
    @GetMapping(value = "/strategies/{id}/rationale", produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> rationale(@PathVariable String id) {
        return repository.findById(id)
                .map(s -> ResponseEntity.ok(rationaleRenderer.renderHtml(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Version history for a strategy (newest first) — backs the revision/diff view. */
    @GetMapping("/strategies/{id}/versions")
    public List<TestStrategy> versions(@PathVariable String id) {
        String lineage = repository.findById(id).map(s -> s.getLineageId() != null ? s.getLineageId() : id).orElse(id);
        return repository.findByLineageIdOrderByVersionDesc(lineage);
    }

    /** Revise one section — stores a new immutable version (deterministic edit, no LLM). */
    @PatchMapping("/strategies/{id}/sections/{key}")
    public TestStrategy reviseSection(@PathVariable String id, @PathVariable String key,
                                      @RequestBody SectionEdit edit) {
        return service.reviseSection(id, key, edit.content(), edit.actor() == null ? "api" : edit.actor());
    }

    /** Regenerate one section with the assistant (optional guidance) — stores a new version. */
    @PostMapping("/strategies/{id}/sections/{key}/regenerate")
    public TestStrategy regenerateSection(@PathVariable String id, @PathVariable String key,
                                          @RequestBody(required = false) RegenRequest req) {
        return service.regenerateSection(id, key, req != null ? req.guidance() : null,
                req != null && req.actor() != null ? req.actor() : "api");
    }

    /** Approve a strategy version (locks it as the basis release plans pin to). */
    @PostMapping("/strategies/{id}/approve")
    public TestStrategy approve(@PathVariable String id, @RequestBody(required = false) ApproveRequest req) {
        return service.approve(id, req != null && req.actor() != null ? req.actor() : "api");
    }

    public record SectionEdit(String content, String actor) {}

    public record RegenRequest(String guidance, String actor) {}

    public record ApproveRequest(String actor) {}

    @PostMapping("/services/{service}/strategies")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TestStrategy generate(@PathVariable("service") String svc, @RequestBody GenerateRequest req) {
        return service.generate(svc, req.basis(), req.source(), req.owner() == null ? "api" : req.owner());
    }

    public record GenerateRequest(String basis, String source, String owner) {}
}
