package ca.bnc.qe.veritas.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.contract.SpecInput;
import ca.bnc.qe.veritas.contract.ValidationRequest;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.junit.jupiter.api.Test;

class PreflightTest {

    private ConnectionsProperties props() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getBitbucket().setBaseUrl("https://bitbucket.bnc");
        return p;
    }

    private ValidationRequest req(String appId, String repoSlug, Path repoPath, List<SpecInput> specs) {
        return new ValidationRequest("svc", appId, repoSlug, null, repoPath, specs, false, "owner");
    }

    @Test
    void passesWhenRepoPathAndSpecPresent() {
        Preflight pf = new Preflight(k -> Optional.empty(), props());
        assertThatCode(() -> pf.validateContract(
                req(null, null, Path.of("repo"), List.of(new SpecInput("s", "openapi: 3.0.0")))))
                .doesNotThrowAnyException();
    }

    @Test
    void failsWithGuidanceWhenRepoAndSpecMissing() {
        Preflight pf = new Preflight(k -> Optional.empty(), props());
        assertThatThrownBy(() -> pf.validateContract(req(null, null, null, List.of())))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("No repo source")
                .hasMessageContaining("No OpenAPI/Swagger spec")
                .hasMessageContaining("veritas doctor");
    }

    @Test
    void appRepoWithoutGitTokenIsBlocked() {
        SecretProvider noSecrets = k -> Optional.empty();
        Preflight pf = new Preflight(noSecrets, props());
        assertThatThrownBy(() -> pf.validateContract(
                req("APP7571", "ciam-policies", null, List.of(new SpecInput("s", "openapi: 3.0.0")))))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Git token missing");
    }

    // ---- token-scope preconditions (Workstream E) ----

    private Preflight withSecret(String key, String value) {
        SecretProvider sp = k -> key.equals(k) ? Optional.of(value) : Optional.empty();
        return new Preflight(sp, props());
    }

    @Test
    void jiraWriteScopeRequiresToken() {
        assertThatThrownBy(() -> new Preflight(k -> Optional.empty(), props()).requireJiraWriteScope("CIAM"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Jira write token missing")
                .hasMessageContaining("CIAM");
        assertThatCode(() -> withSecret("JIRA_API_TOKEN", "pat").requireJiraWriteScope("CIAM"))
                .doesNotThrowAnyException();
    }

    @Test
    void xrayServerDcWriteScopeAcceptsJiraToken() {
        ConnectionsProperties p = props();
        p.getXray().setEdition("SERVER_DC");
        assertThatThrownBy(() -> new Preflight(k -> Optional.empty(), p).requireXrayWriteScope())
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Xray (Server/DC Raven) write token missing");
        SecretProvider jiraOnly = k -> "JIRA_API_TOKEN".equals(k) ? Optional.of("pat") : Optional.empty();
        assertThatCode(() -> new Preflight(jiraOnly, p).requireXrayWriteScope()).doesNotThrowAnyException();
    }

    @Test
    void requireLlmFailsClearlyWhenUnavailableAndPassesWhenAvailable() {
        Preflight pf = new Preflight(k -> Optional.empty(), props());
        assertThatThrownBy(() -> pf.requireLlm(unavailableLlm(), "test-strategy"))
                .isInstanceOf(CopilotAuthRequiredException.class)   // distinct so the UI can open sign-in
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Copilot is not connected");
        assertThatThrownBy(() -> pf.requireLlm(null, "test-strategy"))
                .isInstanceOf(CopilotAuthRequiredException.class);
        assertThatCode(() -> pf.requireLlm(availableLlm(), "test-strategy")).doesNotThrowAnyException();
    }

    private ca.bnc.qe.veritas.llm.LlmGateway unavailableLlm() {
        return new ca.bnc.qe.veritas.llm.LlmGateway() {
            public boolean isAvailable() {
                return false;
            }

            public String complete(String prompt, String model) {
                return "";
            }
        };
    }

    private ca.bnc.qe.veritas.llm.LlmGateway availableLlm() {
        return new ca.bnc.qe.veritas.llm.LlmGateway() {
            public boolean isAvailable() {
                return true;
            }

            public String complete(String prompt, String model) {
                return "";
            }
        };
    }

    @Test
    void gitWriteScopeRequiresToken() {
        assertThatThrownBy(() -> new Preflight(k -> Optional.empty(), props()).requireGitWriteScope())
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("Git write token missing");
        assertThatCode(() -> withSecret("GIT_TOKEN", "pat").requireGitWriteScope()).doesNotThrowAnyException();
    }
}
