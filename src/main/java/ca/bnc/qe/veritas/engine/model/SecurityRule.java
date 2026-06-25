package ca.bnc.qe.veritas.engine.model;

import java.util.List;
import java.util.Set;

/**
 * One authorization rule parsed from a Spring Security {@code authorizeHttpRequests} chain —
 * {@code requestMatchers(method?, pattern).authenticated()/.hasRole(...)/.permitAll()/...}. Rules are evaluated in
 * source order and the FIRST one matching a request wins (Spring semantics); {@code anyRequest()} is the explicit
 * trailing default. Only used to resolve filter-chain security CONSERVATIVELY — see the resolver's guards.
 *
 * @param methods    HTTP methods the matcher restricts to; empty = any method
 * @param pattern    the Ant URL pattern (string-literal only; non-literal matchers make the whole chain ambiguous)
 * @param access     what the rule grants
 * @param roles      the literal role/authority names for {@link Access#ROLE}/{@link Access#AUTHORITY}
 * @param anyRequest true for the {@code anyRequest()} default (distinguished from an explicit {@code "/**"} matcher)
 */
public record SecurityRule(Set<HttpMethod> methods, String pattern, Access access, List<String> roles,
                           boolean anyRequest) {

    public enum Access { PERMIT_ALL, AUTHENTICATED, ROLE, AUTHORITY, DENY_ALL, UNKNOWN }

    public SecurityRule {
        methods = methods == null ? Set.of() : Set.copyOf(methods);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
