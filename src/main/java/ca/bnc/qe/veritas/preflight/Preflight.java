package ca.bnc.qe.veritas.preflight;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.contract.ValidationRequest;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.springframework.stereotype.Service;

/**
 * Fail-fast input/config validation run BEFORE a skill starts. Each check that fails carries a concrete
 * remediation, so the user is told exactly what to provide or configure rather than hitting a mid-run error.
 */
@Service
public class Preflight {

    private final SecretProvider secrets;
    private final ConnectionsProperties connections;

    public Preflight(SecretProvider secrets, ConnectionsProperties connections) {
        this.secrets = secrets;
        this.connections = connections;
    }

    /** Preconditions for a contract-validation run. Throws {@link PreconditionException} if any blocker fails. */
    public void validateContract(ValidationRequest req) {
        List<String> problems = new ArrayList<>();
        boolean hasRepoPath = req.repoPath() != null;
        boolean hasAppRepo = notBlank(req.appId()) && notBlank(req.repoSlug());

        if (!hasRepoPath && !hasAppRepo) {
            problems.add("No repo source. Provide a local path (--repo <path>) OR an app-id + repo "
                    + "(--app-id <projectKey> --repo-slug <slug>) so Veritas can read the code.");
        }
        if (req.specs() == null || req.specs().stream().noneMatch(s -> notBlank(s.content()))) {
            problems.add("No OpenAPI/Swagger spec supplied. Provide at least one spec source "
                    + "(a path in the repo, a live /v3/api-docs URL, or a Confluence page).");
        }
        if (!hasRepoPath && hasAppRepo) {
            if (blank(connections.getBitbucket().getBaseUrl())) {
                problems.add("Bitbucket base URL not configured. Set veritas.connections.bitbucket.base-url "
                        + "(and .workspace) so Veritas can discover and clone the repo.");
            }
            if (secrets.get("GIT_TOKEN").isEmpty()) {
                problems.add("Git token missing. Set the GIT_TOKEN secret (and GIT_USERNAME for app-password "
                        + "auth) so Veritas can clone the repo over HTTPS.");
            }
        }
        if (!problems.isEmpty()) {
            throw new PreconditionException("validate-contract", problems);
        }
    }

    // ---- input preconditions for the other skills (clear "what to provide" messages) ----

    public void createDefect(String findingId, String projectKey) {
        require("create-defect", ordered("finding id", findingId, "Jira project key", projectKey));
    }

    public void testStrategy(String serviceName, String basis) {
        require("test-strategy", ordered("service name", serviceName, "test basis (code or stories)", basis));
    }

    public void createTestCases(String serviceName, String basis) {
        require("create-test-cases", ordered("service name", serviceName, "test basis", basis));
    }

    public void reviewTestCases(String jql) {
        require("review-test-cases", ordered("Xray JQL (which tests to review)", jql));
    }

    public void releaseTestPlan(String fixVersion, String issuesJql, boolean createGaps, String projectKey) {
        List<String> problems = new ArrayList<>();
        if (blank(fixVersion) && blank(issuesJql)) {
            problems.add("Provide a release fixVersion (or an explicit issues JQL) so Veritas knows the release scope.");
        }
        if (createGaps && blank(projectKey)) {
            problems.add("Creating gap tests requires a project key (--project <key>) for the new Xray Test issues.");
        }
        if (!problems.isEmpty()) {
            throw new PreconditionException("release-test-plan", problems);
        }
    }

    public void implementTests(String serviceName, Object serviceRepo, Object templateSource, Object outputRepo) {
        List<String> problems = new ArrayList<>();
        if (blank(serviceName)) {
            problems.add("Service name is required.");
        }
        if (serviceRepo == null) {
            problems.add("Service repo (code under test) is required.");
        }
        if (templateSource == null) {
            problems.add("Template source is required — Veritas mirrors its framework, structure, and naming.");
        }
        if (outputRepo == null) {
            problems.add("Output repo/dir is required — where the generated tests are written.");
        }
        if (!problems.isEmpty()) {
            throw new PreconditionException("implement-tests", problems);
        }
    }

    /**
     * Require an available LLM before a GENERATIVE skill (strategy / plan / cases / review / implement). Unlike
     * contract-validation — which has a deterministic core and degrades to diff-only when Copilot is absent — these
     * skills cannot run without the model, so fail fast with a clear remediation instead of a cryptic mid-run parse
     * error (blind spot #11).
     */
    public void requireLlm(ca.bnc.qe.veritas.llm.LlmGateway llm, String skill) {
        if (llm == null || !llm.isAvailable()) {
            // Distinct from a generic precondition: the UI catches this code and opens the Copilot sign-in.
            throw new CopilotAuthRequiredException(skill);
        }
    }

    // ---- token-scope preconditions checked at the moment of an outward write (after the human gate) ----
    // These fail fast with the exact missing permission so a run never dies on a mid-flight 401/403. A true
    // server-side scope probe needs a live BNC instance (Workstream F); locally we enforce token presence by
    // edition, which prevents the common "no token configured" failure.

    /** Require a Jira write token before creating issues (defects, Test Plans). */
    public void requireJiraWriteScope(String projectKey) {
        if (!present("JIRA_API_TOKEN")) {
            throw new PreconditionException("jira-write", List.of(
                    "Jira write token missing. Set the JIRA_API_TOKEN secret (a PAT authorized to create issues"
                            + (notBlank(projectKey) ? " in " + projectKey : "") + ") before creating Jira issues."));
        }
    }

    /** Require an Xray write token before creating/modifying tests (edition-aware). */
    public void requireXrayWriteScope() {
        boolean cloud = "CLOUD".equalsIgnoreCase(connections.getXray().getEdition());
        boolean ok = cloud
                ? present("XRAY_CLIENT_ID") && present("XRAY_CLIENT_SECRET")
                : present("XRAY_API_TOKEN") || present("JIRA_API_TOKEN");
        if (!ok) {
            throw new PreconditionException("xray-write", List.of(cloud
                    ? "Xray (Cloud) credentials missing. Set XRAY_CLIENT_ID and XRAY_CLIENT_SECRET with test-write scope."
                    : "Xray (Server/DC Raven) write token missing. Set XRAY_API_TOKEN (or reuse JIRA_API_TOKEN) "
                            + "authorized to create/modify tests."));
        }
    }

    /** Require a git write token before pushing a branch / opening a PR. */
    public void requireGitWriteScope() {
        if (!present("GIT_TOKEN")) {
            throw new PreconditionException("git-write", List.of(
                    "Git write token missing. Set the GIT_TOKEN secret (authorized for repo-write + pull-request) "
                            + "before pushing or opening a PR."));
        }
    }

    private boolean present(String key) {
        return secrets.get(key).map(this::notBlank).orElse(false);
    }

    private void require(String skill, Map<String, String> required) {
        List<String> problems = new ArrayList<>();
        required.forEach((name, value) -> {
            if (blank(value)) {
                problems.add(capitalize(name) + " is required.");
            }
        });
        if (!problems.isEmpty()) {
            throw new PreconditionException(skill, problems);
        }
    }

    private Map<String, String> ordered(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
