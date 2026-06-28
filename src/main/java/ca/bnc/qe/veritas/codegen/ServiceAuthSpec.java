package ca.bnc.qe.veritas.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A service's declared auth-token requirements for test generation — the user's answer to "does this service need a
 * token, and how is it generated?". A service needs 0..N <em>token groups</em> (the BNC autotests framework's
 * {@code service_auth.{group}}); each group authenticates one API "type" and yields its own {@code WorldKey.{NAME}_TOKEN}.
 *
 * <p>Veritas stores only the <em>shape</em> — env-var <strong>names</strong>, mechanisms, path mappings — and NEVER a
 * secret value. Credentials reach the generated {@code config.yml} as {@code $sensitive:ENV_VAR} references; the secret
 * itself stays in the user's environment. {@link #toPromptBlock()} renders the deterministic {@code SERVICE_AUTH_SPEC}
 * the codegen prompt consumes, so the LLM only WIRES the token-creation code that already lives in the template.
 */
public record ServiceAuthSpec(List<ServiceAuthGroup> groups) {

    /** How a group's token is generated — each reads its credential from a Windows environment variable. */
    public enum Mechanism { PRIVATE_KEY, BASIC_AUTH, OAUTH2_CLIENT_CREDENTIALS }

    /**
     * One token group.
     *
     * @param name           group key → {@code service_auth.{name}} and {@code WorldKey.{NAME}_TOKEN} (e.g. {@code tpps})
     * @param mechanism      how the token is generated
     * @param envVars        role → env-var <strong>name</strong> (e.g. {@code privateKey→CIAM_TPPS_PRIVATE_KEY},
     *                       or {@code clientId}/{@code clientSecret}); values are NEVER stored, only the names
     * @param pathPrefixes   URL path prefixes whose endpoints use this token; empty ⇒ all endpoints
     * @param xrayRequirement optional Xray requirement key for {@code service_auth.{group}.xray_requirement}
     */
    public record ServiceAuthGroup(String name, Mechanism mechanism, Map<String, String> envVars,
                                   List<String> pathPrefixes, String xrayRequirement) {}

    /** A public service — no token. */
    public static ServiceAuthSpec none() {
        return new ServiceAuthSpec(List.of());
    }

    public boolean isEmpty() {
        return groups == null || groups.isEmpty();
    }

    /** Normalized, uppercase group key used for {@code WorldKey.{KEY}_TOKEN} (e.g. {@code TPPS}). */
    private static String key(String name) {
        String n = (name == null || name.isBlank()) ? "primary" : name.trim();
        return n.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
    }

    /**
     * Renders the {@code SERVICE_AUTH_SPEC} block the codegen prompt consumes — deterministic facts (group →
     * {@code WorldKey}, mechanism, {@code $sensitive:} env refs, path prefixes) plus the wiring instruction. The
     * token-creation code already exists in the template; the LLM only maps tokens to endpoints and wires them.
     */
    public String toPromptBlock() {
        if (isEmpty()) {
            return "This service is PUBLIC — no token is required. Call every endpoint WITHOUT an Authorization header "
                    + "(use the no-auth call variant). Do not emit a service_auth section in config.yml.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("This service needs ").append(groups.size()).append(" token group(s). For each endpoint, pick the ")
                .append("group whose pathPrefix matches its path (longest match wins); an endpoint matching no group is ")
                .append("called WITHOUT a token. The token-creation code already exists in the framework/template — only ")
                .append("WIRE it (do not invent crypto).\n");
        for (ServiceAuthGroup g : groups) {
            String key = key(g.name());
            sb.append("\n- group \"").append(key.toLowerCase(Locale.ROOT)).append("\": pull WorldKey.").append(key)
                    .append("_TOKEN; mechanism=").append(g.mechanism() == null ? "(unspecified)" : g.mechanism())
                    .append("; credentials=").append(renderEnvRefs(g.envVars()))
                    .append("; appliesToPaths=").append(g.pathPrefixes() == null || g.pathPrefixes().isEmpty()
                            ? "[all endpoints]" : g.pathPrefixes().toString());
            if (g.xrayRequirement() != null && !g.xrayRequirement().isBlank()) {
                sb.append("; xrayRequirement=").append(g.xrayRequirement().trim());
            }
        }
        sb.append("\n\nFor each group, emit/patch config.yml service_auth.{group} using the $sensitive: env refs above ")
                .append("(NEVER a literal secret). In each base test, generate the group's token per the template and ")
                .append("pull WorldKey.{GROUP}_TOKEN, then use the authed call variant (e.g. rest().get(endpoint, jwt)) ")
                .append("for that group's endpoints.");
        return sb.toString();
    }

    private static String renderEnvRefs(Map<String, String> envVars) {
        if (envVars == null || envVars.isEmpty()) {
            return "(none declared — emit TODO-FILL and add a todos entry)";
        }
        List<String> refs = new ArrayList<>();
        envVars.forEach((role, var) -> {
            if (var != null && !var.isBlank()) {
                refs.add(role + "=$sensitive:" + var.trim());
            }
        });
        return refs.isEmpty() ? "(none declared — emit TODO-FILL and add a todos entry)" : String.join(", ", refs);
    }
}
