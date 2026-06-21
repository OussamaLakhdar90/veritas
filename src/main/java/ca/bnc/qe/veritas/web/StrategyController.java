package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.testmgmt.TestStrategyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    public StrategyController(TestStrategyRepository repository, TestStrategyService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping("/services/{service}/strategies")
    public List<TestStrategy> list(@PathVariable String service) {
        return repository.findByServiceNameOrderByCreatedAtDesc(service);
    }

    @GetMapping("/strategies/{id}")
    public ResponseEntity<TestStrategy> get(@PathVariable String id) {
        return repository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/services/{service}/strategies")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TestStrategy generate(@PathVariable("service") String svc, @RequestBody GenerateRequest req) {
        return service.generate(svc, req.basis(), req.source(), req.owner() == null ? "api" : req.owner());
    }

    public record GenerateRequest(String basis, String source, String owner) {}
}
