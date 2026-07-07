package ca.bnc.qe.veritas.integration.jira;

/**
 * Builds a Jira "browse" link from a configured base URL and an issue key — the one place that knows the
 * {@code <base>/browse/<KEY>} shape. Returns {@code null} (never a broken link) when the base URL or key is
 * missing, so callers can omit the link cleanly. Shared by the defect flow and the Snyk bulk-fix flow.
 */
public final class JiraLinks {

    private JiraLinks() {
    }

    /** {@code <base>/browse/<key>}, or {@code null} if either is blank. Trailing slashes on the base are trimmed. */
    public static String browseUrl(String base, String key) {
        if (base == null || base.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + key;
    }
}
