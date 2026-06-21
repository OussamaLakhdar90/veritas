package ca.bnc.qe.veritas.preflight;

import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reports whether Veritas is configured to run, with concrete remediation for anything missing — the engine
 * behind {@code veritas doctor} and {@code GET /api/v1/preflight}. Notably it checks the GitHub → Copilot
 * device-flow sign-in (required for {@code llm.mode=http}) and the git token (required to clone repos).
 */
@Service
public class ConfigDoctor {

    /** status: OK (ready) · WARN (works but not ideal) · MISSING (blocks the related skills). */
    public record Check(String name, String status, String detail, String remediation) {}

    private final SecretProvider secrets;
    private final ConnectionsProperties connections;
    private final CopilotAuthService copilotAuth;

    @Value("${veritas.llm.mode:mock}")
    private String llmMode;

    public ConfigDoctor(SecretProvider secrets, ConnectionsProperties connections, CopilotAuthService copilotAuth) {
        this.secrets = secrets;
        this.connections = connections;
        this.copilotAuth = copilotAuth;
    }

    public List<Check> report() {
        List<Check> checks = new ArrayList<>();

        // 1) LLM / Copilot access — the user must authenticate with GitHub to use Copilot (HTTP mode).
        if ("http".equalsIgnoreCase(llmMode)) {
            boolean authed = copilotAuth.isAuthenticated();
            checks.add(new Check("Copilot (GitHub device-flow auth)", authed ? "OK" : "MISSING",
                    authed ? "Signed in; Copilot session token available." : "Not signed in to GitHub Copilot.",
                    authed ? "" : "Run `veritas copilot-login` to authorize this app with GitHub and unlock Copilot."));
        } else if ("copilot".equalsIgnoreCase(llmMode)) {
            checks.add(new Check("Copilot (CLI)", "WARN", "Using the `copilot` CLI gateway.",
                    "Ensure the `copilot` binary is installed and on PATH (veritas.llm.binary)."));
        } else {
            checks.add(new Check("LLM gateway", "WARN", "Running in mock mode — no real Copilot output.",
                    "Set veritas.llm.mode=http and run `veritas copilot-login`, or =copilot for the CLI."));
        }

        // 2) Git access — needed to clone repos for validation/codegen.
        checks.add(secret("GIT_TOKEN", "Git access (clone)",
                "Set the GIT_TOKEN secret (and GIT_USERNAME for app-password auth) to clone Bitbucket repos."));
        checks.add(baseUrl(connections.getBitbucket().getBaseUrl(), "Bitbucket base URL",
                "Set veritas.connections.bitbucket.base-url and .workspace."));

        // 3) Jira (defects + test management).
        checks.add(baseUrl(connections.getJira().getBaseUrl(), "Jira base URL",
                "Set veritas.connections.jira.base-url (edition=" + connections.getJira().getEdition() + ")."));
        checks.add(secret("JIRA_API_TOKEN", "Jira token",
                "Set the JIRA_API_TOKEN secret (Bearer PAT for SERVER_DC, or API token for CLOUD)."));

        // 4) Xray (test management) — Server/DC Raven reuses the Jira host + token.
        boolean xrayTokenOk = secrets.get("XRAY_API_TOKEN").isPresent() || secrets.get("JIRA_API_TOKEN").isPresent();
        checks.add(new Check("Xray token", xrayTokenOk ? "OK" : "MISSING",
                "Xray edition=" + connections.getXray().getEdition(),
                xrayTokenOk ? "" : "Set XRAY_API_TOKEN (or reuse JIRA_API_TOKEN for SERVER_DC Raven)."));

        // 5) Confluence (spec/story ingestion) — optional.
        if (blank(connections.getConfluence().getBaseUrl())) {
            checks.add(new Check("Confluence base URL", "WARN", "Not set (only needed for Confluence specs/stories).",
                    "Set veritas.connections.confluence.base-url if you ingest specs/stories from Confluence."));
        } else {
            checks.add(new Check("Confluence base URL", "OK", connections.getConfluence().getBaseUrl(), ""));
        }
        return checks;
    }

    private Check secret(String key, String name, String remediation) {
        boolean present = secrets.get(key).map(v -> !v.isBlank()).orElse(false);
        return new Check(name, present ? "OK" : "MISSING", present ? "Configured." : "Not set.",
                present ? "" : remediation);
    }

    private Check baseUrl(String value, String name, String remediation) {
        boolean set = !blank(value);
        return new Check(name, set ? "OK" : "MISSING", set ? value : "Not set.", set ? "" : remediation);
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
