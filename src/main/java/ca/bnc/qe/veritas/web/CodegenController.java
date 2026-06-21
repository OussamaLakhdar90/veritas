package ca.bnc.qe.veritas.web;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.codegen.CodegenService;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Codegen runs (implement-tests): list/inspect, and the gated push+PR step. */
@RestController
@RequestMapping("/api/v1")
public class CodegenController {

    private final CodegenRunRepository runs;
    private final CodegenService codegen;

    public CodegenController(CodegenRunRepository runs, CodegenService codegen) {
        this.runs = runs;
        this.codegen = codegen;
    }

    @GetMapping("/codegen-runs")
    public List<CodegenRun> list() {
        return runs.findAll();
    }

    /**
     * Trigger implement-tests: learn the template → analyze the service → generate + build-verify.
     * Returns 202 with the run (files preview + build status + TODOs). The push/PR stays gated via publish.
     */
    @PostMapping("/services/{service}/implement-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodegenRun implementTests(@PathVariable String service, @RequestBody ImplementRequest req) {
        // templatePath is optional — null/blank uses the bundled BNC autotests template.
        Path template = req.templatePath() == null || req.templatePath().isBlank() ? null : Path.of(req.templatePath());
        return codegen.generate(service, Path.of(req.serviceRepo()), template,
                Path.of(req.outputDir()), req.owner() == null ? "api" : req.owner());
    }

    @GetMapping("/codegen-runs/{id}")
    public ResponseEntity<CodegenRun> get(@PathVariable String id) {
        return runs.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Gated: branch + commit + push the generated repo and open a PR. */
    @PostMapping("/codegen-runs/{id}/publish")
    public CodegenRun publish(@PathVariable String id,
                              @RequestParam String repoSlug,
                              @RequestParam(required = false) String targetBranch,
                              @RequestParam(required = false, defaultValue = "api") String owner,
                              @RequestParam(required = false, defaultValue = "false") boolean allowFailedBuild) {
        return codegen.publish(id, repoSlug, targetBranch, owner, allowFailedBuild);
    }

    public record ImplementRequest(String serviceRepo, String templatePath, String outputDir, String owner) {}
}
