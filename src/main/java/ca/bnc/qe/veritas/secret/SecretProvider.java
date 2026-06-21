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
        return get(key).orElseThrow(() ->
                new IllegalStateException("Missing required secret '" + key + "'. Configure it for the current user."));
    }
}
