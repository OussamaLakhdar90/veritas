package ca.bnc.qe.veritas.codegen;

import java.util.List;

/**
 * A service's declared authentication for test generation — modeled on the BNC <strong>lsist</strong> framework's real
 * flow: an OAuth access token minted via <strong>Okta private-key JWT assertion</strong> ({@code RobotToken}). The user
 * declares the Okta token endpoint, the client id, the private-key field (read from {@code oktaCredentials.json}), and
 * the API's OAuth scopes; the generated tests then call
 * {@code {ServiceName}TokenHelper.getToken(testData, Scope)} and pass one {@code WorldKey.ROBOT_TOKEN} into every
 * {@code rest()} call.
 *
 * <p>Veritas stores only URLs, the client id, the private-key <em>field name</em>, and scope strings — <strong>never the
 * private key itself</strong> (that stays in {@code oktaCredentials.json} as a {@code $sensitive:} value).
 * {@link #toPromptBlock()} renders the deterministic {@code SERVICE_AUTH_SPEC} the codegen prompt consumes.
 */
public record ServiceAuthSpec(boolean authenticated, String tokenUrl, String clientId, String privateKeyField,
                              String credentialsFile, List<Scope> scopes) {

    /** One OAuth scope: enum-constant name (e.g. {@code READ}/{@code WRITE}/{@code DELETE}) → the Okta scope string. */
    public record Scope(String name, String value) {}

    /** A public service — no token. */
    public static ServiceAuthSpec none() {
        return new ServiceAuthSpec(false, null, null, null, null, List.of());
    }

    public boolean isEmpty() {
        return !authenticated;
    }

    /** The credentials file the private key is read from, defaulting to {@code oktaCredentials.json}. */
    public String credentialsFileOrDefault() {
        return credentialsFile == null || credentialsFile.isBlank() ? "oktaCredentials.json" : credentialsFile.trim();
    }

    /**
     * Renders the {@code SERVICE_AUTH_SPEC} block the codegen prompt consumes — the Okta private-key-JWT facts (token
     * URL, client id, private-key field, scopes) plus the wiring instruction. {@code RobotToken}/{@code TokenHelper}
     * are framework/template code; the LLM only fills the per-service values and wires the calls.
     */
    public String toPromptBlock() {
        if (!authenticated) {
            return "This service is PUBLIC — no token is required. Call every endpoint without a token "
                    + "(rest().get(endpoint, context)). Do not generate a TokenHelper, a Scope enum, or oktaCredentials.json.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("This service authenticates via Okta PRIVATE-KEY JWT assertion (RobotToken). Generate a ")
                .append("{ServiceName}TokenHelper (static getToken(testData, scope) -> ")
                .append("new RobotToken().getOktaTokenWithPrivateKey(privateKey, Set.of(scope.getValue()), tokenUrl, clientId)) ")
                .append("and a {ServiceName}Scope enum, store one WorldKey.ROBOT_TOKEN, and pass it (+ a data-loaded ")
                .append("context) into every rest() call: rest().post(endpoint, jwt, body, context).\n")
                .append("- AUTH_SERVER_TOKEN_URL: ").append(orTodo(tokenUrl)).append("\n")
                .append("- CLIENT_ID: ").append(orTodo(clientId)).append("\n")
                .append("- private key: read from ").append(credentialsFileOrDefault()).append(" field ")
                .append(orTodo(privateKeyField)).append(" as a $sensitive: value (NEVER a literal)\n")
                .append("- scopes (enum constant -> Okta scope string):");
        if (scopes == null || scopes.isEmpty()) {
            sb.append(" (none declared — emit READ/WRITE/DELETE as TODO-FILL)");
        } else {
            for (Scope s : scopes) {
                if (s != null && s.name() != null) {
                    sb.append("\n    ").append(s.name()).append(" = \"").append(s.value() == null ? "TODO-FILL" : s.value()).append("\"");
                }
            }
        }
        sb.append("\nRequest WRITE for create/update (POST/PUT/PATCH), READ for get/list (GET), DELETE for delete.");
        return sb.toString();
    }

    private static String orTodo(String s) {
        return s == null || s.isBlank() ? "TODO-FILL" : s.trim();
    }
}
