package ca.bnc.qe.veritas.secret;

import java.util.Optional;

/**
 * Resolves per-user secrets (tokens) by key. Secrets are NEVER persisted in the DB/DTOs/logs and never live
 * in {@code application.yml}. Keys: GIT_USERNAME, GIT_TOKEN, JIRA_USERNAME, JIRA_API_TOKEN,
 * CONFLUENCE_API_TOKEN, XRAY_CLIENT_ID, XRAY_CLIENT_SECRET.
 */
public interface SecretProvider {
    Optional<String> get(String key);

    default String require(String key) {
        // A missing per-user token is the user's config problem, not a server fault: surface it as a friendly
        // 422 (code "secret-required") the dashboard keys on, not an opaque 500.
        return get(key).orElseThrow(() -> new SecretRequiredException(key));
    }
}
