package ca.bnc.qe.veritas.settings;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.vcs.GitHost;
import org.springframework.stereotype.Service;

/**
 * Live "Test Connection" probe: a cheap, read-only authenticated call per integration (reusing each client's
 * real auth path) classified into reachable / authenticated / status / message. Never exposes a token — the
 * clients authenticate via headers and their error messages carry no secret.
 */
@Service
public class ConnectionTester {

    private static final Pattern HTTP_STATUS = Pattern.compile("\\b([1-5]\\d\\d)\\b");

    private final JiraClient jira;
    private final GitHost git;
    private final ConfluenceClient confluence;
    private final CopilotAuthService copilot;
    private final ConnectionsProperties connections;
    private final SecretProvider secrets;

    public ConnectionTester(JiraClient jira, GitHost git, ConfluenceClient confluence,
                            CopilotAuthService copilot, ConnectionsProperties connections, SecretProvider secrets) {
        this.jira = jira;
        this.git = git;
        this.confluence = confluence;
        this.copilot = copilot;
        this.connections = connections;
        this.secrets = secrets;
    }

    public ConnectionTestResult test(String service) {
        if (service == null) {
            throw new IllegalArgumentException("Service is required.");
        }
        return switch (service.toLowerCase(java.util.Locale.ROOT)) {
            case "jira" -> probe("jira",
                    notBlank(connections.getJira().getBaseUrl()) && present("JIRA_API_TOKEN"), jira::whoAmI);
            case "bitbucket" -> probe("bitbucket",
                    notBlank(connections.getBitbucket().getBaseUrl()) && present("GIT_TOKEN"), git::whoAmI);
            case "confluence" -> probe("confluence",
                    notBlank(connections.getConfluence().getBaseUrl()) && present("CONFLUENCE_API_TOKEN"),
                    confluence::whoAmI);
            // BNC Xray (Server/DC Raven) lives on the Jira host and reuses the Jira token, so we probe Jira auth.
            case "xray" -> probe("xray",
                    notBlank(connections.getJira().getBaseUrl()) && (present("XRAY_API_TOKEN") || present("JIRA_API_TOKEN")),
                    jira::whoAmI);
            case "copilot" -> probeCopilot();
            default -> throw new IllegalArgumentException("Unknown service '" + service
                    + "'. Expected: jira, bitbucket, confluence, xray, copilot.");
        };
    }

    private ConnectionTestResult probe(String service, boolean configured, Supplier<String> call) {
        if (!configured) {
            return new ConnectionTestResult(service, false, false, 0,
                    "Not configured — set the base URL and token first.");
        }
        try {
            return new ConnectionTestResult(service, true, true, 200, "Connected as " + call.get() + ".");
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int status = parseStatus(msg);
            if (status == 0 && containsAny(msg, "timed out", "Connection", "UnknownHost", "refused", "resolve", "I/O error")) {
                return new ConnectionTestResult(service, false, false, 0,
                        "Could not reach the host — check the base URL and network.");
            }
            if (status == 401 || status == 403 || msg.contains("Unauthorized") || msg.contains("Forbidden")) {
                return new ConnectionTestResult(service, true, false, status == 0 ? 401 : status,
                        "Reached the host, but the token was rejected — check the token and its scopes.");
            }
            return new ConnectionTestResult(service, true, false, status,
                    "Reached the host but the request failed" + (status > 0 ? " (HTTP " + status + ")" : "") + ".");
        }
    }

    private ConnectionTestResult probeCopilot() {
        if (!copilot.isAuthenticated()) {
            return new ConnectionTestResult("copilot", true, false, 0, "Not signed in — use Copilot sign-in.");
        }
        try {
            copilot.getSessionToken();
            return new ConnectionTestResult("copilot", true, true, 200, "Signed in; Copilot session token OK.");
        } catch (RuntimeException e) {
            return new ConnectionTestResult("copilot", true, false, 0,
                    "Signed in, but the Copilot token exchange failed — sign in again.");
        }
    }

    private int parseStatus(String msg) {
        Matcher m = HTTP_STATUS.matcher(msg);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private boolean present(String key) {
        return secrets.get(key).map(v -> !v.isBlank()).orElse(false);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
