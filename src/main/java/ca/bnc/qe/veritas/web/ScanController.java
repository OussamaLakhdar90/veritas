package ca.bnc.qe.veritas.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.contract.ContractValidationService;
import ca.bnc.qe.veritas.contract.SpecInput;
import ca.bnc.qe.veritas.contract.SpecResolver;
import ca.bnc.qe.veritas.contract.SpecSource;
import ca.bnc.qe.veritas.contract.SpecSourceKind;
import ca.bnc.qe.veritas.contract.ValidationRequest;
import ca.bnc.qe.veritas.contract.ValidationResult;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Triggers a contract-validation scan (used by the dashboard "Validate" action). */
@RestController
@RequestMapping("/api/v1")
public class ScanController {

    private final ContractValidationService service;
    private final WorkspaceService workspace;
    private final SpecResolver specResolver;

    public ScanController(ContractValidationService service, WorkspaceService workspace, SpecResolver specResolver) {
        this.service = service;
        this.workspace = workspace;
        this.specResolver = specResolver;
    }

    @PostMapping("/scans")
    public ValidationResult scan(@RequestBody ScanRequest req) {
        Path repoPath = workspace.resolve(req.appId(), req.repoSlug(), req.branch(), req.repoPath());
        List<SpecInput> specs = new ArrayList<>();
        // backward-compatible: bare paths are repo-relative spec files
        if (req.specPaths() != null) {
            for (String sp : req.specPaths()) {
                specs.add(specResolver.resolve(new SpecSource(SpecSourceKind.REPO_PATH, sp), repoPath));
            }
        }
        // explicit sources: repo path, live /v3/api-docs URL, or Confluence page id
        if (req.specSources() != null) {
            for (SpecSourceDto d : req.specSources()) {
                specs.add(specResolver.resolve(
                        new SpecSource(SpecSourceKind.valueOf(d.kind()), d.ref()), repoPath));
            }
        }
        String serviceName = req.serviceName() != null ? req.serviceName()
                : (req.repoSlug() != null ? req.repoSlug() : repoPath.getFileName().toString());
        boolean llm = req.llmEnabled() == null || req.llmEnabled();
        return service.validate(new ValidationRequest(serviceName, req.appId(), req.repoSlug(), req.branch(),
                repoPath, specs, llm, "api"));
    }

    public record SpecSourceDto(String kind, String ref) {}

    public record ScanRequest(String serviceName, String appId, String repoSlug, String branch,
                              String repoPath, List<String> specPaths, List<SpecSourceDto> specSources,
                              Boolean llmEnabled) {}
}
