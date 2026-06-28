package ca.bnc.qe.veritas.codegen;

import java.util.List;
import java.util.Locale;

/**
 * A service's declared authentication for test generation. A project needs <strong>0..N token groups</strong> — one per
 * API "type"/group it calls. Each group is its own <strong>Okta token source</strong> (the BNC lsist framework mints
 * tokens via Okta private-key JWT assertion, {@code RobotToken}): a token URL, a client id, the private-key field (read
 * from {@code oktaCredentials.json}), and the OAuth scopes — yielding its own {@code {Name}TokenHelper} and
 * {@code WorldKey.{NAME}_TOKEN}. Endpoints map to a group by URL path prefix.
 *
 * <p>Veritas stores only URLs, client ids, the private-key <em>field name</em>, scope strings, and path prefixes —
 * <strong>never the private key itself</strong> (that stays in {@code oktaCredentials.json} as a {@code $sensitive:}
 * value). {@link #toPromptBlock()} renders the deterministic {@code SERVICE_AUTH_SPEC} the codegen prompt consumes.
 */
public record ServiceAuthSpec(List<ServiceAuthGroup> groups) {

    /** One OAuth scope: enum-constant name (e.g. {@code READ}/{@code WRITE}/{@code DELETE}) → the Okta scope string. */
    public record Scope(String name, String value) {}

    /**
     * One token group / API group — its own Okta token source plus the endpoints (by path prefix) that use its token.
     *
     * @param name          group key → {@code WorldKey.{NAME}_TOKEN} and {@code {Name}TokenHelper} (e.g. {@code tpps})
     * @param tokenUrl      Okta token endpoint ({@code AUTH_SERVER_TOKEN_URL})
     * @param clientId      Okta client id ({@code CLIENT_ID})
     * @param privateKeyField the field in the credentials file holding the private key (a {@code $sensitive:} value)
     * @param credentialsFile the credentials file (defaults to {@code oktaCredentials.json})
     * @param scopes        the OAuth scopes this group requests
     * @param pathPrefixes  URL path prefixes whose endpoints use this token; empty ⇒ all endpoints
     */
    public record ServiceAuthGroup(String name, String tokenUrl, String clientId, String privateKeyField,
                                   String credentialsFile, List<Scope> scopes, List<String> pathPrefixes) {

        public String credentialsFileOrDefault() {
            return credentialsFile == null || credentialsFile.isBlank() ? "oktaCredentials.json" : credentialsFile.trim();
        }
    }

    /** A public service — no token. */
    public static ServiceAuthSpec none() {
        return new ServiceAuthSpec(List.of());
    }

    public boolean isEmpty() {
        return groups == null || groups.isEmpty();
    }

    /** Uppercase WorldKey key for a group name (e.g. {@code tpps} → {@code TPPS}). */
    private static String key(String name) {
        String n = (name == null || name.isBlank()) ? "robot" : name.trim();
        return n.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
    }

    /**
     * Renders the {@code SERVICE_AUTH_SPEC} block the codegen prompt consumes — for each group the Okta facts (token
     * URL, client id, private-key field, scopes), its {@code WorldKey.{NAME}_TOKEN}, and the path prefixes that select
     * it. The Okta/{@code RobotToken} machinery is framework code; the LLM fills the per-group values and wires calls.
     */
    public String toPromptBlock() {
        if (isEmpty()) {
            return "This service is PUBLIC — no token is required. Call every endpoint without a token "
                    + "(rest().get(endpoint, context)). Do not generate a TokenHelper, a Scope enum, or oktaCredentials.json.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("This service needs ").append(groups.size()).append(" token group(s). Each authenticates via Okta ")
                .append("PRIVATE-KEY JWT assertion (RobotToken) and gets its own {Name}TokenHelper + {Name}Scope enum + ")
                .append("WorldKey.{NAME}_TOKEN. For each endpoint, pick the group whose pathPrefix matches its path ")
                .append("(longest match wins); an endpoint matching no group is called WITHOUT a token. Pass the group's ")
                .append("token (+ a data-loaded context) into every rest() call: rest().post(endpoint, jwt, body, context).\n");
        for (ServiceAuthGroup g : groups) {
            String key = key(g.name());
            sb.append("\n- group \"").append(key.toLowerCase(Locale.ROOT)).append("\": WorldKey.").append(key)
                    .append("_TOKEN via ").append(capitalize(key)).append("TokenHelper.getToken(testData, scope) -> ")
                    .append("RobotToken.getOktaTokenWithPrivateKey(privateKey, Set.of(scope.getValue()), tokenUrl, clientId)\n")
                    .append("    AUTH_SERVER_TOKEN_URL: ").append(orTodo(g.tokenUrl())).append("\n")
                    .append("    CLIENT_ID: ").append(orTodo(g.clientId())).append("\n")
                    .append("    private key: from ").append(g.credentialsFileOrDefault()).append(" field ")
                    .append(orTodo(g.privateKeyField())).append(" ($sensitive:, NEVER a literal)\n")
                    .append("    scopes: ").append(renderScopes(g.scopes())).append("\n")
                    .append("    appliesToPaths: ").append(g.pathPrefixes() == null || g.pathPrefixes().isEmpty()
                            ? "[all endpoints]" : g.pathPrefixes().toString());
        }
        sb.append("\n\nRequest WRITE for create/update (POST/PUT/PATCH), READ for get/list (GET), DELETE for delete.");
        return sb.toString();
    }

    private static String renderScopes(List<Scope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "(none declared — emit READ/WRITE/DELETE as TODO-FILL)";
        }
        StringBuilder sb = new StringBuilder();
        for (Scope s : scopes) {
            if (s != null && s.name() != null) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(s.name()).append("=\"").append(s.value() == null ? "TODO-FILL" : s.value()).append("\"");
            }
        }
        return sb.length() == 0 ? "(none declared — emit READ/WRITE/DELETE as TODO-FILL)" : sb.toString();
    }

    private static String capitalize(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String orTodo(String s) {
        return s == null || s.isBlank() ? "TODO-FILL" : s.trim();
    }
}
