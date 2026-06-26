package ca.bnc.qe.veritas.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.contract.ContractValidationService;
import ca.bnc.qe.veritas.contract.Thoroughness;
import ca.bnc.qe.veritas.contract.ValidationRequest;
import ca.bnc.qe.veritas.contract.ValidationResult;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.testmgmt.BasisBuilder;
import ca.bnc.qe.veritas.testmgmt.CreateTestCasesService;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import picocli.CommandLine;

/**
 * Branch coverage for the two contract/test-case CLI commands driven through picocli's
 * {@code execute(args)} — guards, defaults, every option branch, and the {@code finally} cleanup.
 * Services are mocked so we assert on the {@link ValidationRequest}/push arguments, exit codes, and
 * which workspace/service calls fire.
 */
class CliCommandsCoverageTest {

    // ---- ValidateContractCommand collaborators ----
    private final ContractValidationService validateService = mock(ContractValidationService.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);

    // ---- CreateTestCasesCommand collaborators ----
    private final CreateTestCasesService casesService = mock(CreateTestCasesService.class);
    private final BasisBuilder basisBuilder = mock(BasisBuilder.class);

    private int runValidate(String... args) {
        return new CommandLine(new ValidateContractCommand(validateService, workspace)).execute(args);
    }

    private int runCreate(String... args) {
        return new CommandLine(new CreateTestCasesCommand(casesService, basisBuilder, workspace)).execute(args);
    }

    private ValidationResult result(String status) {
        return new ValidationResult("scan-1", status, 3,
                Map.of("HIGH", 1L, "LOW", 2L), null, null, null, 0.1234);
    }

    private TestCase testCase(double cost) {
        TestCase tc = new TestCase();
        tc.setId("tc-1");
        tc.setTitle("a case");
        tc.setEstCostUsd(cost);
        return tc;
    }

    // ============================ ValidateContractCommand ============================

    @Test
    void validateMissingSpecIsAUsageError() {
        // --spec is required → picocli aborts before call(), exit code 2, nothing touched.
        assertThat(runValidate("--repo", "/tmp/repo")).isEqualTo(2);
        verifyNoInteractions(validateService, workspace);
    }

    @Test
    void validateCompletedReturnsZeroAndDefaultsNameToFolder(@TempDir Path repo) throws Exception {
        Path spec = repo.resolve("api.yaml");
        Files.writeString(spec, "openapi: 3.0.0");
        when(workspace.resolve(null, null, null, repo.toString())).thenReturn(repo);
        when(validateService.validate(any())).thenReturn(result("COMPLETED"));

        assertThat(runValidate("--repo", repo.toString(), "--spec", spec.toString())).isZero();

        ArgumentCaptor<ValidationRequest> req = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(validateService).validate(req.capture());
        ValidationRequest r = req.getValue();
        // no --name and no --repo-slug → service name falls back to the repo folder name.
        assertThat(r.serviceName()).isEqualTo(repo.getFileName().toString());
        assertThat(r.llmEnabled()).isTrue();
        assertThat(r.thoroughness()).isEqualTo(Thoroughness.STANDARD);
        assertThat(r.specs()).singleElement()
                .satisfies(s -> {
                    assertThat(s.id()).isEqualTo("api");          // stem strips the .yaml extension
                    assertThat(s.content()).isEqualTo("openapi: 3.0.0");
                });
        verify(workspace).cleanup(repo);   // finally always cleans up
    }

    @Test
    void validateNonCompletedStatusReturnsOne(@TempDir Path repo) throws Exception {
        Path spec = repo.resolve("api.yaml");
        Files.writeString(spec, "x");
        when(workspace.resolve(any(), any(), any(), eq(repo.toString()))).thenReturn(repo);
        when(validateService.validate(any())).thenReturn(result("FAILED"));

        assertThat(runValidate("--repo", repo.toString(), "--spec", spec.toString())).isEqualTo(1);
        verify(workspace).cleanup(repo);
    }

    @Test
    void validateExplicitNameAndNoLlmAndThoroughnessAreHonoured(@TempDir Path repo) throws Exception {
        Path spec = repo.resolve("openapi.json");
        Files.writeString(spec, "{}");
        when(workspace.resolve(any(), any(), any(), eq(repo.toString()))).thenReturn(repo);
        when(validateService.validate(any())).thenReturn(result("COMPLETED"));

        assertThat(runValidate("--repo", repo.toString(), "--spec", spec.toString(),
                "--name", "ciam-svc", "--no-llm", "--thoroughness", "deep")).isZero();

        ArgumentCaptor<ValidationRequest> req = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(validateService).validate(req.capture());
        ValidationRequest r = req.getValue();
        assertThat(r.serviceName()).isEqualTo("ciam-svc");   // explicit --name wins
        assertThat(r.llmEnabled()).isFalse();                // --no-llm
        assertThat(r.thoroughness()).isEqualTo(Thoroughness.DEEP);
        assertThat(r.specs().get(0).id()).isEqualTo("openapi");
    }

    @Test
    void validateUnknownThoroughnessFallsBackToStandard(@TempDir Path repo) throws Exception {
        Path spec = repo.resolve("api.yaml");
        Files.writeString(spec, "x");
        when(workspace.resolve(any(), any(), any(), eq(repo.toString()))).thenReturn(repo);
        when(validateService.validate(any())).thenReturn(result("COMPLETED"));

        assertThat(runValidate("--repo", repo.toString(), "--spec", spec.toString(),
                "--thoroughness", "bogus")).isZero();

        ArgumentCaptor<ValidationRequest> req = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(validateService).validate(req.capture());
        assertThat(req.getValue().thoroughness()).isEqualTo(Thoroughness.STANDARD);
    }

    @Test
    void validateRepoSlugBecomesServiceNameWhenNoNameGiven(@TempDir Path clone) throws Exception {
        // --app-id + --repo-slug path: workspace resolves a clone, repoSlug names the service.
        Path spec = clone.resolve("contract.yaml");
        Files.writeString(spec, "openapi: 3.1.0");
        when(workspace.resolve("APP", "my-repo", "develop", null)).thenReturn(clone);
        when(validateService.validate(any())).thenReturn(result("COMPLETED"));

        assertThat(runValidate("--app-id", "APP", "--repo-slug", "my-repo", "--branch", "develop",
                "--spec", "contract.yaml")).isZero();   // relative spec resolves against the clone

        ArgumentCaptor<ValidationRequest> req = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(validateService).validate(req.capture());
        ValidationRequest r = req.getValue();
        assertThat(r.serviceName()).isEqualTo("my-repo");
        assertThat(r.appId()).isEqualTo("APP");
        assertThat(r.repoSlug()).isEqualTo("my-repo");
        assertThat(r.gitRef()).isEqualTo("develop");
        assertThat(r.specs().get(0).content()).isEqualTo("openapi: 3.1.0");
        verify(workspace).resolve("APP", "my-repo", "develop", null);
        verify(workspace).cleanup(clone);
    }

    @Test
    void validateMultipleSpecsAreAllRead(@TempDir Path repo) throws Exception {
        Path a = repo.resolve("a.yaml");
        Path b = repo.resolve("b.yml");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");
        when(workspace.resolve(any(), any(), any(), eq(repo.toString()))).thenReturn(repo);
        when(validateService.validate(any())).thenReturn(result("COMPLETED"));

        assertThat(runValidate("--repo", repo.toString(),
                "--spec", a.toString(), "--spec", b.toString())).isZero();

        ArgumentCaptor<ValidationRequest> req = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(validateService).validate(req.capture());
        assertThat(req.getValue().specs()).hasSize(2)
                .extracting("id").containsExactly("a", "b");
    }

    @Test
    void validatePrintsReportAndPdfAndCorrectedYamlPaths(@TempDir Path repo) throws Exception {
        Path spec = repo.resolve("api.yaml");
        Files.writeString(spec, "x");
        when(workspace.resolve(any(), any(), any(), eq(repo.toString()))).thenReturn(repo);
        // exercise the three non-null report-path branches in the printout.
        when(validateService.validate(any())).thenReturn(new ValidationResult(
                "scan-9", "COMPLETED", 0, Map.of(),
                "/out/report.html", "/out/report.pdf", "/out/corrected.yaml", 0.0));

        assertThat(runValidate("--repo", repo.toString(), "--spec", spec.toString())).isZero();
        verify(validateService).validate(any());
        verify(workspace).cleanup(repo);
    }

    @Test
    void validateCleansUpEvenWhenServiceThrows(@TempDir Path repo) throws Exception {
        Path spec = repo.resolve("api.yaml");
        Files.writeString(spec, "x");
        when(workspace.resolve(any(), any(), any(), eq(repo.toString()))).thenReturn(repo);
        when(validateService.validate(any())).thenThrow(new IllegalStateException("boom"));

        // an exception out of call() becomes a non-zero picocli exit, but finally still cleans up.
        assertThat(runValidate("--repo", repo.toString(), "--spec", spec.toString())).isNotZero();
        verify(workspace).cleanup(repo);
    }

    // ============================ CreateTestCasesCommand ============================

    @Test
    void createMissingNameIsAUsageError() {
        // --name is required → picocli aborts, exit 2, nothing generated.
        assertThat(runCreate("--repo", "/tmp/repo")).isEqualTo(2);
        verifyNoInteractions(casesService, basisBuilder, workspace);
    }

    @Test
    void createFromLocalRepoBuildsBasisAndCleansUp() {
        Path repo = Path.of("/tmp/repo");
        when(workspace.resolve(null, null, null, repo.toString())).thenReturn(repo);
        when(basisBuilder.fromRepo(repo)).thenReturn("BASIS");
        when(casesService.generate("ciam", "BASIS", "local")).thenReturn(List.of(testCase(0.05), testCase(0.05)));

        assertThat(runCreate("--name", "ciam", "--repo", repo.toString())).isZero();

        verify(basisBuilder).fromRepo(repo);
        verify(casesService).generate("ciam", "BASIS", "local");
        verify(workspace).cleanup(repo);     // clone temp dir dropped after the basis is in memory
        verify(casesService, never()).pushToXray(any(), anyString(), anyString());
    }

    @Test
    void createFromAppIdAndRepoSlugUsesCodebaseBasis() {
        Path clone = Path.of("/tmp/clone");
        when(workspace.resolve("APP", "repo", "main", null)).thenReturn(clone);
        when(basisBuilder.fromRepo(clone)).thenReturn("CODE");
        when(casesService.generate("ciam", "CODE", "local")).thenReturn(List.of(testCase(0.01)));

        assertThat(runCreate("--name", "ciam", "--app-id", "APP", "--repo-slug", "repo", "--branch", "main")).isZero();

        verify(workspace).resolve("APP", "repo", "main", null);
        verify(basisBuilder).fromRepo(clone);
        verify(workspace).cleanup(clone);
    }

    @Test
    void createFromIngestUsesJqlAndConfluencePagesWithoutCloning() {
        when(basisBuilder.fromIngest(eq("project = CIAM"), eq(List.of("111", "222"))))
                .thenReturn("INGEST");
        when(casesService.generate("ciam", "INGEST", "local")).thenReturn(List.of(testCase(0.02)));

        assertThat(runCreate("--name", "ciam", "--jql", "project = CIAM", "--confluence", "111,222")).isZero();

        verify(basisBuilder).fromIngest("project = CIAM", List.of("111", "222"));
        verifyNoInteractions(workspace);   // no code arm → no clone
    }

    @Test
    void createFromIngestWithNoConfluenceUsesEmptyPageList() {
        when(basisBuilder.fromIngest(eq("project = CIAM"), eq(List.of()))).thenReturn("INGEST");
        when(casesService.generate("ciam", "INGEST", "local")).thenReturn(List.of());

        assertThat(runCreate("--name", "ciam", "--jql", "project = CIAM")).isZero();

        verify(basisBuilder).fromIngest("project = CIAM", List.of());
    }

    @Test
    void createPushWithoutProjectReturnsTwoAndDoesNotGenerate() {
        // --push needs --project → fail fast before any generation.
        assertThat(runCreate("--name", "ciam", "--jql", "x", "--push")).isEqualTo(2);
        verifyNoInteractions(casesService);
    }

    @Test
    void createPushWithBlankProjectAlsoReturnsTwo() {
        assertThat(runCreate("--name", "ciam", "--jql", "x", "--push", "--project", "   ")).isEqualTo(2);
        verifyNoInteractions(casesService);
    }

    @Test
    void createPushWithProjectPushesEachCaseToXray() {
        when(basisBuilder.fromIngest(any(), any())).thenReturn("INGEST");
        TestCase a = testCase(0.03);
        TestCase b = testCase(0.07);
        when(casesService.generate("ciam", "INGEST", "local")).thenReturn(List.of(a, b));

        assertThat(runCreate("--name", "ciam", "--jql", "x", "--push", "--project", "CIAM")).isZero();

        verify(casesService).pushToXray(a, "CIAM", "local");
        verify(casesService).pushToXray(b, "CIAM", "local");
        verify(casesService, times(2)).pushToXray(any(), eq("CIAM"), eq("local"));
    }

    @Test
    void createWithoutPushGeneratesButDoesNotPush() {
        when(basisBuilder.fromIngest(any(), any())).thenReturn("INGEST");
        when(casesService.generate("ciam", "INGEST", "local")).thenReturn(List.of(testCase(0.1)));

        assertThat(runCreate("--name", "ciam", "--jql", "x")).isZero();

        verify(casesService).generate("ciam", "INGEST", "local");
        verify(casesService, never()).pushToXray(any(), anyString(), anyString());
    }
}