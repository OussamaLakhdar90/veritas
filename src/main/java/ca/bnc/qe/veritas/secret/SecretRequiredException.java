package ca.bnc.qe.veritas.secret;

import java.util.List;
import ca.bnc.qe.veritas.preflight.PreconditionException;

/**
 * Thrown when a step needs a per-user secret (an API token) that isn't configured yet. A specialization of
 * {@link PreconditionException} so existing 422 handling still applies, but the API maps it to a distinct
 * {@code code: "secret-required"} (plus the missing {@code key}) so the dashboard can pop an inline "connect
 * this token" panel instead of showing an opaque error — mirroring {@code copilot-auth-required}.
 */
public class SecretRequiredException extends PreconditionException {

    private static final long serialVersionUID = 1L;

    /** Stable discriminator the frontend matches on (RFC-7807 problem {@code code}). */
    public static final String CODE = "secret-required";

    private final transient String key;

    public SecretRequiredException(String key) {
        super("settings-secrets", List.of(hint(key)));
        this.key = key;
    }

    /** The missing secret key (e.g. {@code SNYK_API_TOKEN}) — echoed to the client so the UI can deep-link Settings. */
    public String getKey() {
        return key;
    }

    /** A short, non-technical remediation per known key; generic fallback otherwise. */
    private static String hint(String key) {
        return switch (key == null ? "" : key) {
            case "SNYK_API_TOKEN" -> "Connect your Snyk token in Settings, then try again.";
            case "JIRA_API_TOKEN", "JIRA_USERNAME" -> "Connect your Jira credentials in Settings, then try again.";
            case "CONFLUENCE_API_TOKEN" -> "Connect your Confluence token in Settings, then try again.";
            case "GIT_TOKEN", "GIT_USERNAME" -> "Connect your Git credentials in Settings, then try again.";
            case "XRAY_API_TOKEN", "XRAY_CLIENT_ID", "XRAY_CLIENT_SECRET" ->
                    "Connect your Xray credentials in Settings, then try again.";
            default -> "Configure the '" + key + "' secret in Settings, then try again.";
        };
    }
}
