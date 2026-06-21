package ca.bnc.qe.veritas.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.contract.ContractValidationService;
import ca.bnc.qe.veritas.contract.SpecInput;
import ca.bnc.qe.veritas.contract.ValidationRequest;
import ca.bnc.qe.veritas.contract.ValidationResult;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Validate an OpenAPI/Swagger contract against a Spring Boot codebase. Use either a local {@code --repo},
 * or {@code --app-id} + {@code --repo-slug} (+ optional {@code --branch}) to discover & clone from Bitbucket
 * Cloud. Multiple {@code --spec} values (repo YAML + Confluence YAML) trigger spec-vs-spec drift detection;
 * relative spec paths resolve against the repo working copy.
 */
@Component
@Command(name = "validate-contract",
        description = "Validate an OpenAPI/Swagger spec against a Spring Boot codebase; emit findings + report.")
public class ValidateContractCommand implements Callable<Integer> {

    private final ContractValidationService service;
    private final WorkspaceService workspace;

    @Option(names = {"-r", "--repo"}, description = "Local path to the Spring Boot source root.")
    private Path repo;

    @Option(names = "--app-id", description = "Bitbucket app-id (project key) to discover the repo from.")
    private String appId;

    @Option(names = "--repo-slug", description = "Repo slug to clone under the app-id.")
    private String repoSlug;

    @Option(names = "--branch", description = "Branch to clone (default: repo default branch).")
    private String branch;

    @Option(names = "--spec", required = true, description = "OpenAPI/Swagger spec path (repeatable; relative paths resolve against the repo).")
    private List<Path> specs;

    @Option(names = {"-n", "--name"}, description = "Service name (defaults to repo slug / folder name).")
    private String name;

    @Option(names = "--no-llm", description = "Skip the LLM reconcile step (deterministic findings only).")
    private boolean noLlm;

    public ValidateContractCommand(ContractValidationService service, WorkspaceService workspace) {
        this.service = service;
        this.workspace = workspace;
    }

    @Override
    public Integer call() throws Exception {
        Path repoPath = workspace.resolve(appId, repoSlug, branch, repo == null ? null : repo.toString());

        List<SpecInput> specInputs = new ArrayList<>();
        for (Path p : specs) {
            Path resolved = p.isAbsolute() ? p : repoPath.resolve(p.toString());
            specInputs.add(new SpecInput(stem(resolved), Files.readString(resolved)));
        }
        String serviceName = name != null ? name
                : (repoSlug != null ? repoSlug : repoPath.toAbsolutePath().getFileName().toString());

        ValidationRequest request = new ValidationRequest(serviceName, appId, repoSlug, branch,
                repoPath, specInputs, !noLlm, "local");
        ValidationResult result = service.validate(request);

        System.out.println("Scan " + result.scanId() + " -> " + result.status());
        System.out.println("Findings: " + result.totalFindings() + " " + result.bySeverity());
        System.out.printf("Est. LLM cost: $%.4f%n", result.estCostUsd());
        if (result.reportPath() != null) {
            System.out.println("Report: " + result.reportPath());
        }
        if (result.reportPdfPath() != null) {
            System.out.println("Report (PDF): " + result.reportPdfPath());
        }
        if (result.correctedYamlPath() != null) {
            System.out.println("Corrected YAML: " + result.correctedYamlPath());
        }
        return "COMPLETED".equals(result.status()) ? 0 : 1;
    }

    private String stem(Path p) {
        String f = p.getFileName().toString();
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }
}
