package ca.bnc.qe.veritas.web;

import java.util.List;
import ca.bnc.qe.veritas.codegen.CodegenService;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
