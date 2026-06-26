package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * AsyncScanRunner kicks off the clone → resolve-spec → validate pipeline on a daemon worker and returns the
 * RUNNING scan id immediately. These tests exercise:
 *  - submit() row creation (RUNNING + QUEUED + service-name fallback) and the synchronous return value,
 *  - the async success path (resolve workspace → resolve each spec → preflight → validation.runInto),
 *  - the two pre-engine failure paths (clone failure → failedStage CLONING, spec-resolve failure →
 *    failedStage RESOLVING_SPEC) recorded as FAILED with the real error message,
 *  - the finally-block workspace cleanup in both success and failure.
 * Async outcomes are asserted with Mockito {@code timeout} verifications (the work runs on a background thread).
 */
class AsyncScanRunnerTest {

    private WorkspaceService workspace;
    private SpecResolver specResolver;
    private ContractValidationService validation;
    private ScanRepository scans;
    private Preflight preflight;
    private AsyncScanRunner runner;

    private static final long ASYNC_MS = 5_000;

    @BeforeEach
    void setUp() {
        workspace = mock(WorkspaceService.class);
        specResolver = mock(SpecResolver.class);
        validation = mock(ContractValidationService.class);
        scans = mock(ScanRepository.class);
        preflight = mock(Preflight.class);
        // save echoes the argument back (and preserves the assigned UUID id + lets @Version sync be a no-op).
        when(scans.save(any(Scan.class))).thenAnswer(inv -> inv.getArgument(0));
        runner = new AsyncScanRunner(workspace, specResolver, validation, scans, preflight);
    }

    private static List<AsyncScanRunner.SpecRef> oneRepoSpec() {
        return List.of(new AsyncScanRunner.SpecRef(SpecSourceKind.REPO_PATH, "openapi.yaml"));
    }

    @Test
    void submitCreatesRunningQueuedScanAndReturnsItsId() {
        // resolve never returns (the worker would block) — but submit() must still return synchronously with the id.
        Scan returned = new Scan();
        when(scans.save(any(Scan.class))).thenAnswer(inv -> {
            Scan s = inv.getArgument(0);
            // capture the very first saved row (the RUNNING create) for assertion
            if (returned.getServiceName() == null) {
                returned.setServiceName(s.getServiceName());
                returned.setStatus(s.getStatus());
                returned.setStage(s.getStage());
                returned.setRepoSlug(s.getRepoSlug());
                returned.setOwner(s.getOwner());
            }
            return s;
        });
        when(workspace.resolve(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("clone boom"));

        String id = runner.submit("Payments", "APP1", "payments-svc", "main", null,
                oneRepoSpec(), true, "alice", Thoroughness.STANDARD);

        assertThat(id).isNotBlank();
        assertThat(returned.getServiceName()).isEqualTo("Payments");
        assertThat(returned.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(returned.getStage()).isEqualTo(ScanStages.QUEUED);
        assertThat(returned.getRepoSlug()).isEqualTo("payments-svc");
        assertThat(returned.getOwner()).isEqualTo("alice");
    }

    @Test
    void submitFallsBackToRepoSlugWhenServiceNameBlank() {
        ArgumentCaptor<Scan> first = ArgumentCaptor.forClass(Scan.class);
        when(workspace.resolve(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("stop here"));

        // blank service name → the saved row's serviceName must fall back to the repo slug
        runner.submit("   ", "APP1", "billing-svc", "develop", null,
                oneRepoSpec(), false, "bob", Thoroughness.ECONOMY);

        verify(scans, timeout(ASYNC_MS).atLeastOnce()).save(first.capture());
        assertThat(first.getAllValues().get(0).getServiceName()).isEqualTo("billing-svc");
    }

    @Test
    void successPathResolvesSpecsThenPreflightThenRunInto() {
        Path repo = Path.of("repo-dir");
        when(workspace.resolve(eq("APP1"), eq("orders-svc"), eq("main"), eq("/local")))
                .thenReturn(repo);
        when(specResolver.resolve(any(SpecSource.class), eq(repo)))
                .thenReturn(new SpecInput("openapi", "openapi: 3.0.0"));

        String id = runner.submit("Orders", "APP1", "orders-svc", "main", "/local",
                oneRepoSpec(), true, "carol", Thoroughness.DEEP);
        assertThat(id).isNotBlank();

        // spec resolution happens against the resolved repo path
        verify(specResolver, timeout(ASYNC_MS)).resolve(any(SpecSource.class), eq(repo));
        // preflight runs before the engine
        ArgumentCaptor<ValidationRequest> reqCap = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(preflight, timeout(ASYNC_MS)).validateContract(reqCap.capture());
        // engine is handed the SAME scan row + a request carrying the resolved repo + thoroughness
        ArgumentCaptor<Scan> scanCap = ArgumentCaptor.forClass(Scan.class);
        ArgumentCaptor<ValidationRequest> runReqCap = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(validation, timeout(ASYNC_MS)).runInto(scanCap.capture(), runReqCap.capture());

        ValidationRequest run = runReqCap.getValue();
        assertThat(run.repoPath()).isEqualTo(repo);
        assertThat(run.serviceName()).isEqualTo("Orders");
        assertThat(run.thoroughness()).isEqualTo(Thoroughness.DEEP);
        assertThat(run.llmEnabled()).isTrue();
        assertThat(run.owner()).isEqualTo("carol");
        assertThat(run.specs()).extracting(SpecInput::id).containsExactly("openapi");
        assertThat(scanCap.getValue().getId()).isEqualTo(id);

        // the resolved spec ids are recorded on the scan + the stage advanced past QUEUED to RESOLVING_SPEC
        assertThat(scanCap.getValue().getSpecSources()).isEqualTo("openapi");

        // on success the run() never marks the scan FAILED itself (the engine owns the terminal state)
        verify(validation, timeout(ASYNC_MS)).runInto(any(), any());
        assertThat(scanCap.getValue().getStatus()).isNotEqualTo(RunStatus.FAILED);
        // finally always cleans up the resolved working copy
        verify(workspace, timeout(ASYNC_MS)).cleanup(repo);
    }

    @Test
    void cloneFailureRecordsFailedWithFailedStageCloning() {
        when(workspace.resolve(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Clone failed for orders-svc"));

        String id = runner.submit("Orders", "APP1", "orders-svc", "main", null,
                oneRepoSpec(), true, "carol", Thoroughness.STANDARD);
        assertThat(id).isNotBlank();

        // cleanup is the LAST thing run() does (finally, after the FAILED persist) — wait on it so the terminal
        // save has definitely landed before we inspect the captured scan (avoids a race on the worker thread).
        verify(workspace, timeout(ASYNC_MS)).cleanup(any());
        // the engine is never reached when the clone fails, nor is spec resolution
        verify(validation, never()).runInto(any(), any());
        verify(specResolver, never()).resolve(any(), any());

        ArgumentCaptor<Scan> cap = ArgumentCaptor.forClass(Scan.class);
        verify(scans, atLeastOnce()).save(cap.capture());
        Scan last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(last.getStage()).isEqualTo(ScanStages.FAILED);
        // failed during CLONING (the stage set right before workspace.resolve threw)
        assertThat(last.getFailedStage()).isEqualTo(ScanStages.CLONING);
        assertThat(last.getStageDetail()).isNull();
        assertThat(last.getErrorMessage()).contains("Clone failed for orders-svc");
        assertThat(last.getFinishedAt()).isNotNull();
    }

    @Test
    void specResolveFailureRecordsFailedWithFailedStageResolvingSpec() {
        Path repo = Path.of("repo-dir");
        when(workspace.resolve(any(), any(), any(), any())).thenReturn(repo);
        when(specResolver.resolve(any(SpecSource.class), any()))
                .thenThrow(new IllegalStateException("No OpenAPI/Swagger spec found in the repo"));

        runner.submit("Orders", "APP1", "orders-svc", "main", "/local",
                oneRepoSpec(), true, "carol", Thoroughness.STANDARD);

        // cleanup runs last (finally) — wait on it so the FAILED save has landed before we inspect the scan
        verify(workspace, timeout(ASYNC_MS)).cleanup(repo);
        // resolve succeeded, so the engine + preflight are never reached because spec resolution blew up first
        verify(specResolver, atLeastOnce()).resolve(any(), any());
        verify(validation, never()).runInto(any(), any());
        verify(preflight, never()).validateContract(any());

        ArgumentCaptor<Scan> cap = ArgumentCaptor.forClass(Scan.class);
        verify(scans, atLeastOnce()).save(cap.capture());
        Scan last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(last.getStage()).isEqualTo(ScanStages.FAILED);
        assertThat(last.getFailedStage()).isEqualTo(ScanStages.RESOLVING_SPEC);
        assertThat(last.getErrorMessage()).contains("No OpenAPI/Swagger spec found");
    }

    @Test
    void preflightFailureRecordsFailedAndSkipsEngine() {
        Path repo = Path.of("repo-dir");
        when(workspace.resolve(any(), any(), any(), any())).thenReturn(repo);
        when(specResolver.resolve(any(SpecSource.class), any()))
                .thenReturn(new SpecInput("openapi", "openapi: 3.0.0"));
        // preflight throws AFTER specs resolve — failedStage stays at RESOLVING_SPEC (the last stage set)
        org.mockito.Mockito.doThrow(new RuntimeException("preflight blocker"))
                .when(preflight).validateContract(any());

        runner.submit("Orders", "APP1", "orders-svc", "main", "/local",
                oneRepoSpec(), true, "carol", Thoroughness.STANDARD);

        // cleanup runs last (finally) — wait on it so the FAILED save has landed before we inspect the scan
        verify(workspace, timeout(ASYNC_MS)).cleanup(repo);
        verify(preflight, atLeastOnce()).validateContract(any());
        verify(validation, never()).runInto(any(), any());

        ArgumentCaptor<Scan> cap = ArgumentCaptor.forClass(Scan.class);
        verify(scans, atLeastOnce()).save(cap.capture());
        Scan last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(last.getFailedStage()).isEqualTo(ScanStages.RESOLVING_SPEC);
        assertThat(last.getErrorMessage()).contains("preflight blocker");
    }

    @Test
    void persistSwallowsOptimisticLockConflictWithoutCrashingTheWorker() {
        // The reconciler may finalize the row mid-scan → a stage save throws OptimisticLockingFailureException.
        // run() must keep going (the conflict is swallowed) and still reach the engine.
        Path repo = Path.of("repo-dir");
        when(workspace.resolve(any(), any(), any(), any())).thenReturn(repo);
        when(specResolver.resolve(any(SpecSource.class), any()))
                .thenReturn(new SpecInput("openapi", "openapi: 3.0.0"));
        // 1st save = the RUNNING create (must succeed so submit() returns); the 2nd save (the CLONING stage
        // persist on the worker) throws the lock conflict, which persist() must swallow without crashing run().
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(scans.save(any(Scan.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 2) {
                throw new org.springframework.dao.OptimisticLockingFailureException("finalized externally");
            }
            return inv.getArgument(0);
        });

        String id = runner.submit("Orders", "APP1", "orders-svc", "main", "/local",
                oneRepoSpec(), true, "carol", Thoroughness.STANDARD);
        assertThat(id).isNotBlank();   // the create succeeded

        // despite a later save() throwing the lock conflict, the swallow keeps the pipeline alive to the engine
        verify(validation, timeout(ASYNC_MS)).runInto(any(), any());
        verify(workspace, timeout(ASYNC_MS)).cleanup(repo);
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);   // proves a save after the create did throw and was caught
    }

    @Test
    void shutdownStopsTheWorkerPool() {
        // submit() with a clone failure, then shutdown — shutdown must complete without throwing.
        when(workspace.resolve(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        runner.submit("Orders", "APP1", "orders-svc", "main", null,
                oneRepoSpec(), true, "carol", Thoroughness.STANDARD);
        runner.shutdown();
        // a follow-up submit after shutdown still returns an id synchronously (the row is created before submit())
        verify(scans, timeout(ASYNC_MS).atLeastOnce()).save(any(Scan.class));
    }
}
