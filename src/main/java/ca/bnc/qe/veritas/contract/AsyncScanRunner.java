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
                         List<SpecRef> specs, boolean llmEnabled, String owner, Thoroughness thoroughness) {
        Scan scan = new Scan();
        scan.setServiceName(serviceName != null && !serviceName.isBlank() ? serviceName : repoSlug);
        scan.setAppId(appId);
        scan.setRepoSlug(repoSlug);
        scan.setGitRef(branch);
        scan.setOwner(owner);
        scan.setStatus(RunStatus.RUNNING);
        scan.setStage(ScanStages.QUEUED);
        scan.setStartedAt(Instant.now());
        scan = scans.save(scan);
        final Scan saved = scan;
        log.info("Scan {} [{}] queued — validating {} on branch '{}'",
                scan.getId(), scan.getServiceName(), repoSlug, branch);
        pool.submit(() -> run(saved, appId, repoSlug, branch, repoPath, specs, llmEnabled, owner, thoroughness));
        return scan.getId();
    }

    /** Updates the live progress stage — drives the dashboard stepper AND logs the advance to the console. */
    private void stage(Scan scan, String stage) {
        scan.setStage(stage);
        persist(scan);
        log.info("Scan {} [{}] → {} — {}", scan.getId(), scan.getServiceName(), stage, ScanStages.describe(stage));
    }

    /** Persist a scan update, syncing the optimistic-lock @Version and swallowing a conflict if the scan was
     *  finalized externally (the stale-timeout reconciler) — we never resurrect a dead scan. */
    private void persist(Scan scan) {
        try {
            Scan saved = scans.save(scan);
            scan.setVersion(saved.getVersion());
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            log.warn("Scan {} was finalized externally — skipping update", scan.getId());
        }
    }

    private void run(Scan scan, String appId, String repoSlug, String branch, String repoPath,
                     List<SpecRef> specRefs, boolean llmEnabled, String owner, Thoroughness thoroughness) {
        Path repo = null;
        try {
            stage(scan, ScanStages.CLONING);
            repo = workspace.resolve(appId, repoSlug, branch, repoPath);

            stage(scan, ScanStages.RESOLVING_SPEC);
            List<SpecInput> specs = new ArrayList<>();
            for (SpecRef r : specRefs) {
                specs.add(specResolver.resolve(new SpecSource(r.kind(), r.ref()), repo));
            }
            scan.setSpecSources(String.join(",", specs.stream().map(SpecInput::id).toList()));
            persist(scan);

            ValidationRequest req = new ValidationRequest(scan.getServiceName(), appId, repoSlug, branch,
                    repo, specs, llmEnabled, owner, thoroughness);
            preflight.validateContract(req);   // clear remediation if inputs/config are missing

            // Hand off to the engine, which continues the stages (EXTRACTING → … → DONE) on the same row.
            validation.runInto(scan, req);
        } catch (Exception e) {
            log.error("Scan {} [{}] → FAILED — {}", scan.getId(), scan.getServiceName(), e.getMessage(), e);
            scan.setFailedStage(scan.getStage());   // CLONING / RESOLVING_SPEC failures land here — keep the real step
            scan.setStatus(RunStatus.FAILED);
            scan.setStage(ScanStages.FAILED);
            scan.setStageDetail(null);
            scan.setErrorMessage(e.getMessage());
            scan.setFinishedAt(Instant.now());
            persist(scan);
        } finally {
            workspace.cleanup(repo);   // delete the cloned temp dir (no-op for a local repoPath or a failed clone)
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
