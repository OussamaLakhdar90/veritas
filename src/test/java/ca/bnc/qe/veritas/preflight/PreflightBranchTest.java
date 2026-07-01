package ca.bnc.qe.veritas.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.contract.SpecInput;
import ca.bnc.qe.veritas.contract.ValidationRequest;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage companion to {@link PreflightTest}. Exercises the uncovered guard branches of every
 * readiness check: blank-spec detection, the bitbucket-base-url branch, repo-path-AND-app-repo skipping the
 * clone checks, the CLOUD vs SERVER_DC Xray edition split, the per-skill {@code require} input checks,
 * the {@code releaseTestPlan}/{@code implementTests} multi-problem aggregation, and the blank-token
 * handling in {@code present}.
 */
class PreflightBranchTest {

    // ---- helpers -----------------------------------------------------------

    /** ConnectionsProperties with a bitbucket base-url already set (so the url branch is satisfied). */
    private ConnectionsProperties props() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getBitbucket().setBaseUrl("https://bitbucket.bnc");
        return p;
    }

    private SecretProvider none() {
        return k -> Optional.empty();
    }

    private SecretProvider secret(String key, String value) {
        return k -> key.equals(k) ? Optional.of(value) : Optional.empty();
    }

    private ValidationRequest req(String appId, String repoSlug, Path repoPath, List<SpecInput> specs) {
        return new ValidationRequest("svc", appId, repoSlug, null, repoPath, specs, false, "owner");
    }

    private List<SpecInput> goodSpec() {
        return List.of(new SpecInput("s", "openapi: 3.0.0"));
    }

    // ---- validateContract: spec-content guard ------------------------------

    @Test
    void specWithNullContentCountsAsNoSpec() {
        Preflight pf = new Preflight(none(), props());
        // repoPath supplied so the repo-source branch passes; the spec branch must still fire on blank content.
        assertThatThrownBy(() -> pf.validateContract(
                req(null, null, Path.of("repo"), List.of(new SpecInput("s", null)))))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("No OpenAPI/Swagger spec")
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(1));
    }

    @Test
    void specWithBlankContentCountsAsNoSpec() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.validateContract(
                req(null, null, Path.of("repo"), List.of(new SpecInput("s", "   ")))))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("No OpenAPI/Swagger spec");
    }

    @Test
    void specWithNullListCountsAsNoSpec() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.validateContract(req(null, null, Path.of("repo"), null)))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("No OpenAPI/Swagger spec");
    }

    // ---- validateContract: app-repo clone-config branches ------------------

    @Test
    void appRepoWithoutBitbucketBaseUrlIsBlocked() {
        ConnectionsProperties noUrl = new ConnectionsProperties();   // base-url left null
        // Git token present so ONLY the bitbucket-url problem surfaces.
        Preflight pf = new Preflight(secret("GIT_TOKEN", "tok"), noUrl);
        assertThatThrownBy(() -> pf.validateContract(req("APP1", "slug", null, goodSpec())))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Bitbucket base URL not configured")
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(1));
    }

    @Test
    void appRepoMissingBothUrlAndTokenReportsBothProblems() {
        ConnectionsProperties noUrl = new ConnectionsProperties();
        Preflight pf = new Preflight(none(), noUrl);
        assertThatThrownBy(() -> pf.validateContract(req("APP1", "slug", null, goodSpec())))
                .isInstanceOf(PreconditionException.class)
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(2));
    }

    @Test
    void appRepoWithBaseUrlAndGitTokenPasses() {
        Preflight pf = new Preflight(secret("GIT_TOKEN", "tok"), props());
        assertThatCode(() -> pf.validateContract(req("APP1", "slug", null, goodSpec())))
                .doesNotThrowAnyException();
    }

    @Test
    void repoPathSetTogetherWithAppRepoSkipsCloneChecks() {
        // hasRepoPath && hasAppRepo -> the (!hasRepoPath && hasAppRepo) block is skipped, so even with NO
        // bitbucket url and NO git token the run is allowed (the local path wins).
        ConnectionsProperties noUrl = new ConnectionsProperties();
        Preflight pf = new Preflight(none(), noUrl);
        assertThatCode(() -> pf.validateContract(req("APP1", "slug", Path.of("repo"), goodSpec())))
                .doesNotThrowAnyException();
    }

    @Test
    void appIdWithoutRepoSlugIsNotAnAppRepoSource() {
        // notBlank(appId) but blank(repoSlug) => hasAppRepo is false => "No repo source" fires.
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.validateContract(req("APP1", null, null, goodSpec())))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("No repo source");
    }

    // ---- createDefect / testStrategy / createTestCases / reviewTestCases ----

    @Test
    void createDefectReportsEachMissingFieldCapitalized() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.createDefect(null, "  "))
                .isInstanceOf(PreconditionException.class)
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> {
                    assertThat(p).containsExactly(
                            "Finding id is required.", "Jira project key is required.");
                });
    }

    @Test
    void createDefectPassesWhenBothPresent() {
        Preflight pf = new Preflight(none(), props());
        assertThatCode(() -> pf.createDefect("F-1", "CIAM")).doesNotThrowAnyException();
    }

    @Test
    void testStrategyRequiresServiceAndBasis() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.testStrategy("", null))
                .isInstanceOf(PreconditionException.class)
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(2));
        assertThatCode(() -> pf.testStrategy("svc", "code")).doesNotThrowAnyException();
    }

    @Test
    void createTestCasesReportsOnlyTheBlankField() {
        Preflight pf = new Preflight(none(), props());
        // service present, basis blank -> exactly one problem.
        assertThatThrownBy(() -> pf.createTestCases("svc", "  "))
                .isInstanceOf(PreconditionException.class)
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> {
                    assertThat(p).containsExactly("Test basis is required.");
                });
    }

    @Test
    void reviewTestCasesRequiresJqlAndPassesWhenPresent() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.reviewTestCases(null))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("review-test-cases");
        assertThatCode(() -> pf.reviewTestCases("project = CIAM and issuetype = Test"))
                .doesNotThrowAnyException();
    }

    // ---- releaseTestPlan ----------------------------------------------------

    @Test
    void releaseTestPlanFailsWhenNeitherVersionNorJql() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.releaseTestPlan(null, "  ", false, null))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("release fixVersion")
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(1));
    }

    @Test
    void releaseTestPlanFixVersionAloneIsEnough() {
        Preflight pf = new Preflight(none(), props());
        assertThatCode(() -> pf.releaseTestPlan("2025.6", null, false, null))
                .doesNotThrowAnyException();
    }

    @Test
    void releaseTestPlanIssuesJqlAloneIsEnough() {
        Preflight pf = new Preflight(none(), props());
        assertThatCode(() -> pf.releaseTestPlan(null, "fixVersion = 2025.6", false, null))
                .doesNotThrowAnyException();
    }

    @Test
    void releaseTestPlanCreateGapsWithoutProjectKeyIsBlocked() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.releaseTestPlan("2025.6", null, true, "  "))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("requires a project key")
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(1));
    }

    @Test
    void releaseTestPlanBothBlankAndCreateGapsReportsTwoProblems() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.releaseTestPlan(null, null, true, null))
                .isInstanceOf(PreconditionException.class)
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(2));
    }

    @Test
    void releaseTestPlanCreateGapsWithProjectKeyPasses() {
        Preflight pf = new Preflight(none(), props());
        assertThatCode(() -> pf.releaseTestPlan("2025.6", null, true, "CIAM"))
                .doesNotThrowAnyException();
    }

    // ---- implementTests -----------------------------------------------------

    @Test
    void implementTestsReportsAllFourMissingInputs() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.implementTests("  ", null, null, null))
                .isInstanceOf(PreconditionException.class)
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(4));
    }

    @Test
    void implementTestsReportsOnlyTheMissingTemplate() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.implementTests("svc", new Object(), null, new Object()))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Template source is required")
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(1));
    }

    @Test
    void implementTestsReportsMissingServiceRepoAndOutputRepo() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.implementTests("svc", null, new Object(), null))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Service repo")
                .hasMessageContaining("Output repo")
                .extracting(e -> ((PreconditionException) e).problems())
                .satisfies(p -> assertThat((List<?>) p).hasSize(2));
    }

    @Test
    void implementTestsPassesWhenAllInputsPresent() {
        Preflight pf = new Preflight(none(), props());
        assertThatCode(() -> pf.implementTests("svc", new Object(), new Object(), new Object()))
                .doesNotThrowAnyException();
    }

    // ---- requireLlm (Mockito gateway) --------------------------------------

    @Test
    void requireLlmThrowsWhenGatewayReportsUnavailable() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isAvailable()).thenReturn(false);
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.requireLlm(llm, "implement-tests"))
                .isInstanceOf(CopilotAuthRequiredException.class)
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("implement-tests")
                .hasMessageContaining("Copilot is not connected");
    }

    @Test
    void requireLlmPassesWhenGatewayAvailable() {
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.isAvailable()).thenReturn(true);
        Preflight pf = new Preflight(none(), props());
        assertThatCode(() -> pf.requireLlm(llm, "test-strategy")).doesNotThrowAnyException();
    }

    @Test
    void copilotAuthExceptionExposesStableCode() {
        // The discriminator the UI matches on must stay stable.
        assertThat(CopilotAuthRequiredException.CODE).isEqualTo("copilot-auth-required");
    }

    // ---- requireJiraWriteScope: blank-project branch -----------------------

    @Test
    void jiraWriteScopeMessageOmitsProjectWhenBlank() {
        Preflight pf = new Preflight(none(), props());
        assertThatThrownBy(() -> pf.requireJiraWriteScope("  "))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Jira write token missing")
                .hasMessageNotContaining("authorized to create issues in ");
    }

    @Test
    void jiraWriteScopeBlankTokenIsTreatedAsMissing() {
        // present() must reject a whitespace-only token value, not just an empty Optional.
        Preflight pf = new Preflight(secret("JIRA_API_TOKEN", "   "), props());
        assertThatThrownBy(() -> pf.requireJiraWriteScope("CIAM"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Jira write token missing");
    }

    // ---- requireXrayWriteScope: CLOUD edition split ------------------------

    private ConnectionsProperties cloudXrayProps() {
        ConnectionsProperties p = props();
        p.getXray().setEdition("CLOUD");
        return p;
    }

    @Test
    void xrayCloudRequiresBothClientIdAndSecret() {
        // Only the client id present -> still blocked (needs both).
        Preflight pf = new Preflight(secret("XRAY_CLIENT_ID", "id"), cloudXrayProps());
        assertThatThrownBy(pf::requireXrayWriteScope)
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Xray (Cloud) credentials missing");
    }

    @Test
    void xrayCloudPassesWithClientIdAndSecret() {
        SecretProvider both = k -> switch (k) {
            case "XRAY_CLIENT_ID" -> Optional.of("id");
            case "XRAY_CLIENT_SECRET" -> Optional.of("secret");
            default -> Optional.empty();
        };
        Preflight pf = new Preflight(both, cloudXrayProps());
        assertThatCode(pf::requireXrayWriteScope).doesNotThrowAnyException();
    }

    @Test
    void xrayCloudEditionIsCaseInsensitive() {
        ConnectionsProperties p = props();
        p.getXray().setEdition("cloud");   // lower-case must still select the cloud branch
        // A server token must NOT satisfy the cloud branch.
        Preflight pf = new Preflight(secret("XRAY_API_TOKEN", "tok"), p);
        assertThatThrownBy(pf::requireXrayWriteScope)
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Xray (Cloud) credentials missing");
    }

    @Test
    void xrayServerDcPassesWithDedicatedXrayToken() {
        ConnectionsProperties p = props();
        p.getXray().setEdition("SERVER_DC");
        Preflight pf = new Preflight(secret("XRAY_API_TOKEN", "tok"), p);
        assertThatCode(pf::requireXrayWriteScope).doesNotThrowAnyException();
    }

    // ---- requireGitWriteScope: blank-token branch --------------------------

    @Test
    void gitWriteScopeBlankTokenIsTreatedAsMissing() {
        Preflight pf = new Preflight(secret("GIT_TOKEN", "   "), props());
        assertThatThrownBy(pf::requireGitWriteScope)
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Git write token missing");
    }
}