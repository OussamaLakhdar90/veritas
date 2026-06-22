package ca.bnc.qe.veritas.web;

import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.contract.AsyncScanRunner;
import ca.bnc.qe.veritas.contract.AsyncScanRunner.SpecRef;
import ca.bnc.qe.veritas.contract.SpecSourceKind;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers a contract-validation scan (the dashboard "Validate" action). Returns immediately with the
 * scan id so the UI can poll {@code GET /scans/{id}} and render a live progress stepper; the clone →
 * resolve-spec → extract → diff → reconcile → report pipeline runs on a background worker.
 */
@RestController
@RequestMapping("/api/v1")
public class ScanController {

    private final AsyncScanRunner runner;

    public ScanController(AsyncScanRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/scans")
    @ResponseStatus(HttpStatus.ACCEPTED)   // long-running validation; the UI polls for stage/status
    public ScanAccepted scan(@RequestBody ScanRequest req) {
        List<SpecRef> specs = new ArrayList<>();
        // backward-compatible: bare paths are repo-relative spec files
        if (req.specPaths() != null) {
            for (String sp : req.specPaths()) {
                specs.add(new SpecRef(SpecSourceKind.REPO_PATH, sp));
            }
        }
        // explicit sources: repo path, live /v3/api-docs URL, or Confluence page id
        if (req.specSources() != null) {
            for (SpecSourceDto d : req.specSources()) {
                specs.add(new SpecRef(SpecSourceKind.valueOf(d.kind()), d.ref()));
            }
        }
        boolean llm = req.llmEnabled() == null || req.llmEnabled();
        String scanId = runner.submit(req.serviceName(), req.appId(), req.repoSlug(), req.branch(),
                req.repoPath(), specs, llm, "api");
        return new ScanAccepted(scanId, "RUNNING");
    }

    public record SpecSourceDto(String kind, String ref) {}

    public record ScanRequest(String serviceName, String appId, String repoSlug, String branch,
                              String repoPath, List<String> specPaths, List<SpecSourceDto> specSources,
                              Boolean llmEnabled) {}

    /** The 202 body: enough for the UI to start polling progress. */
    public record ScanAccepted(String scanId, String status) {}
}
