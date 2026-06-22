package ca.bnc.qe.veritas.contract;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a contract-validation scan asynchronously so the dashboard can return immediately and poll progress.
 * The whole pipeline — clone → resolve spec → extract → diff → reconcile → report — runs on a background
 * thread, updating {@code scan.stage} at each step (the stepper the UI renders). A hard crash mid-scan is
 * recovered by {@code ScanReconciler}; a normal failure is recorded as FAILED with the error message.
 */
@Component
@Slf4j
public class AsyncScanRunner {

    private final WorkspaceService workspace;
    private final SpecResolver specResolver;
    private final ContractValidationService validation;
    private final ScanRepository scans;
    private final Preflight preflight;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "veritas-scan");
        t.setDaemon(true);
        return t;
    });

    public AsyncScanRunner(WorkspaceService workspace, SpecResolver specResolver,
                           ContractValidationService validation, ScanRepository scans, Preflight preflight) {
        this.workspace = workspace;
        this.specResolver = specResolver;
        this.validation = validation;
        this.scans = scans;
        this.preflight = preflight;
    }

    /** One requested spec location (a repo path, a live /v3/api-docs URL, or a Confluence page). */
    public record SpecRef(SpecSourceKind kind, String ref) {}

    /** Create the RUNNING scan row, kick off the pipeline on a worker, and return the id immediately. */
    public String submit(String serviceName, String appId, String repoSlug, String branch, String repoPath,
                         List<SpecRef> specs, boolean llmEnabled, String owner) {
        Scan scan = new Scan();
        scan.setServiceName(serviceName != null && !serviceName.isBlank() ? serviceName : repoSlug);
        scan.setAppId(appId);
        scan.setRepoSlug(repoSlug);
        scan.setGitRef(branch);
        scan.setOwner(owner);
        scan.setStatus(RunStatus.RUNNING);
        scan.setStage("QUEUED");
        scan.setStartedAt(Instant.now());
        scan = scans.save(scan);
        final Scan saved = scan;
        pool.submit(() -> run(saved, appId, repoSlug, branch, repoPath, specs, llmEnabled, owner));
        return scan.getId();
    }

    private void run(Scan scan, String appId, String repoSlug, String branch, String repoPath,
                     List<SpecRef> specRefs, boolean llmEnabled, String owner) {
        try {
            scan.setStage("CLONING");
            scans.save(scan);
            Path repo = workspace.resolve(appId, repoSlug, branch, repoPath);

            scan.setStage("RESOLVING_SPEC");
            scans.save(scan);
            List<SpecInput> specs = new ArrayList<>();
            for (SpecRef r : specRefs) {
                specs.add(specResolver.resolve(new SpecSource(r.kind(), r.ref()), repo));
            }
            scan.setSpecSources(String.join(",", specs.stream().map(SpecInput::id).toList()));
            scans.save(scan);

            ValidationRequest req = new ValidationRequest(scan.getServiceName(), appId, repoSlug, branch,
                    repo, specs, llmEnabled, owner);
            preflight.validateContract(req);   // clear remediation if inputs/config are missing

            // Hand off to the engine, which continues the stages (EXTRACTING → … → DONE) on the same row.
            validation.runInto(scan, req);
        } catch (Exception e) {
            log.error("Async scan {} failed: {}", scan.getId(), e.getMessage());
            scan.setStatus(RunStatus.FAILED);
            scan.setStage("FAILED");
            scan.setErrorMessage(e.getMessage());
            scan.setFinishedAt(Instant.now());
            scans.save(scan);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
