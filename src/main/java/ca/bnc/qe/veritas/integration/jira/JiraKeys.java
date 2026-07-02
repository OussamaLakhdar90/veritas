package ca.bnc.qe.veritas.integration.jira;

/**
 * Validates caller-supplied Jira identifiers before they are concatenated into a REST path. An issue key
 * ({@code CIAM-1234}) or project key ({@code CIAM}) is {@code [A-Za-z0-9_-]} — no {@code /}, {@code ?}, {@code #},
 * {@code %}, or {@code ..}, any of which could rewrite the request path under Veritas's Jira PAT (an authenticated
 * SSRF / path-injection). Both the Server (v2) and Cloud (v3) clients route issue/project keys through this.
 */
final class JiraKeys {

    private JiraKeys() {
    }

    /** Validate an issue key ({@code PROJ-123}); returns it unchanged, or throws on anything path-unsafe. */
    static String issueKey(String key) {
        return require(key, "Jira issue key");
    }

    /** Validate a project key ({@code PROJ}); returns it unchanged, or throws on anything path-unsafe. */
    static String projectKey(String key) {
        return require(key, "Jira project key");
    }

    private static String require(String value, String what) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid " + what + ": '" + value + "'");
        }
        return value;
    }
}
