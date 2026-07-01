package ca.bnc.qe.veritas.engine.extract.java;

import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.firstString;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.getAnnotation;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.has;
import static ca.bnc.qe.veritas.engine.extract.java.AnnotationSupport.stringValues;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.SecurityChain;
import ca.bnc.qe.veritas.engine.model.SecurityRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.util.AntPathMatcher;

/**
 * Stateless helpers that read a Spring project's authorization model — both the centralized {@code SecurityFilterChain}
 * {@code authorizeHttpRequests} DSL (parse + first-match resolve) and per-method/-class annotation security
 * (@PreAuthorize / @Secured / @RolesAllowed / @PermitAll / @DenyAll, incl. composed and unresolved look-alikes). Lifted
 * verbatim out of JavaSpringExtractor (which was well over the S104 line threshold) into a collaborator that
 * static-imports {@link AnnotationSupport}; call sites in the extractor are unchanged (they static-import these).
 */
final class SecuritySupport {

    private SecuritySupport() {
    }

    /** Types that mean the project centralizes Spring Security authorization (vs per-method annotations). */
    private static final Set<String> SECURITY_CONFIG_TYPES = Set.of(
            "SecurityFilterChain", "HttpSecurity", "WebSecurityConfigurerAdapter");

    static boolean usesCentralizedSecurity(List<CompilationUnit> units) {
        return units.stream().anyMatch(cu -> cu.findAll(ClassOrInterfaceType.class).stream()
                .anyMatch(t -> SECURITY_CONFIG_TYPES.contains(t.getNameAsString())));
    }

    static final String CENTRALIZED_SECURITY_BLIND_SPOT =
            "Authorization appears centralized in a Spring Security configuration "
            + "(SecurityFilterChain/HttpSecurity); per-endpoint authorization is enforced there by URL pattern "
            + "and is not visible to annotation-based static analysis.";

    /** Spring matches the FIRST authorize rule whose Ant pattern matches a request, in declaration order. */
    private static final AntPathMatcher ANT = new AntPathMatcher();

    /** A parsed {@code requestMatchers(method?, pattern...)} call: the (optional) method restriction + literal patterns. */
    private record Matcher(Set<HttpMethod> methods, List<String> patterns) {}

    /**
     * Parse the authorization rules from a single {@code SecurityFilterChain} @Bean's {@code authorizeHttpRequests}
     * DSL. Returns {@code null} when there is no such bean / no recognised authorize DSL; a chain flagged
     * {@code ambiguous} when more than one chain bean exists, the chain is URL-scoped by {@code securityMatcher(...)},
     * or any part can't be read as a literal rule — so the resolver declines and the coarse blind spot is kept.
     */
    static SecurityChain parseSecurityChain(List<CompilationUnit> units) {
        List<MethodDeclaration> beans = new ArrayList<>();
        for (CompilationUnit cu : units) {
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                if (m.getType() instanceof ClassOrInterfaceType t && "SecurityFilterChain".equals(t.getNameAsString())) {
                    beans.add(m);
                }
            }
        }
        if (beans.isEmpty()) {
            return null;
        }
        if (beans.size() > 1) {
            return new SecurityChain(List.of(), true);   // multiple chains (each securityMatcher-scoped) — too subtle
        }
        MethodDeclaration bean = beans.get(0);
        MethodCallExpr authz = bean.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("authorizeHttpRequests")
                        || c.getNameAsString().equals("authorizeRequests"))
                .findFirst().orElse(null);
        if (authz == null) {
            return null;   // a SecurityFilterChain without an authorize DSL we read → not our resolvable case
        }
        if (bean.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals("securityMatcher"))) {
            return new SecurityChain(List.of(), true);   // chain scoped to a URL subset → out of conservative scope
        }
        LambdaExpr lambda = authz.getArguments().stream()
                .filter(LambdaExpr.class::isInstance).map(LambdaExpr.class::cast).findFirst().orElse(null);
        if (lambda == null) {
            return new SecurityChain(List.of(), true);   // a method-ref / Customizer object instead of a lambda
        }
        MethodCallExpr outer = outermostChainCall(lambda);
        return outer == null ? new SecurityChain(List.of(), true) : pairRules(flattenChain(outer));
    }

    /** The outermost (terminal) call of the fluent authorize chain in the lambda body, expression or block form. */
    static MethodCallExpr outermostChainCall(LambdaExpr lambda) {
        Expression body = lambda.getExpressionBody().orElse(null);
        if (body == null && lambda.getBody() instanceof BlockStmt block) {
            body = block.getStatements().stream()
                    .filter(ExpressionStmt.class::isInstance)
                    .map(s -> ((ExpressionStmt) s).getExpression())
                    .findFirst().orElse(null);
        }
        return body instanceof MethodCallExpr mc ? mc : null;
    }

    /** Flatten a fluent {@code a.f1().f2().f3()} chain into source order [f1, f2, f3] (innermost call → terminal). */
    static List<MethodCallExpr> flattenChain(MethodCallExpr outer) {
        Deque<MethodCallExpr> chain = new ArrayDeque<>();
        Expression cur = outer;
        while (cur instanceof MethodCallExpr mc) {
            chain.push(mc);
            cur = mc.getScope().orElse(null);
        }
        return new ArrayList<>(chain);
    }

    /** Pair each matcher / {@code anyRequest()} with its immediately-following authorize terminal, in source order. */
    static SecurityChain pairRules(List<MethodCallExpr> chain) {
        List<SecurityRule> rules = new ArrayList<>();
        boolean ambiguous = false;
        boolean sawAnyRequest = false;
        int i = 0;
        while (i < chain.size()) {
            MethodCallExpr call = chain.get(i);
            String name = call.getNameAsString();
            boolean any = name.equals("anyRequest");
            if (any || isMatcher(name)) {
                if (sawAnyRequest) {
                    ambiguous = true;   // anyRequest() must be the terminal rule — Spring rejects a matcher after it
                    break;
                }
                if (i + 1 >= chain.size()) {
                    ambiguous = true;
                    break;
                }
                MethodCallExpr term = chain.get(i + 1);
                SecurityRule.Access acc = accessOf(term.getNameAsString());
                List<String> roles = literalArgs(term);
                if (acc == SecurityRule.Access.UNKNOWN) {
                    ambiguous = true;
                }
                // A ROLE/AUTHORITY terminal must have read EVERY role argument as a string literal — a non-literal
                // (a constant / enum ref) or a zero-arg call would otherwise emit a truncated or empty role set and
                // fabricate security that isn't what the code enforces. When in doubt, decline (keep the blind spot).
                if ((acc == SecurityRule.Access.ROLE || acc == SecurityRule.Access.AUTHORITY)
                        && (roles.isEmpty() || roles.size() != term.getArguments().size())) {
                    ambiguous = true;
                }
                if (any) {
                    rules.add(new SecurityRule(Set.of(), "/**", acc, roles, true));
                    sawAnyRequest = true;
                } else {
                    Matcher mm = parseMatcher(call);
                    if (mm == null) {
                        ambiguous = true;   // a non-literal matcher (constant / regexMatchers / variable)
                    } else {
                        for (String pat : mm.patterns()) {
                            rules.add(new SecurityRule(mm.methods(), pat, acc, roles, false));
                        }
                    }
                }
                i += 2;
            } else {
                ambiguous = true;   // a chain call that is neither a matcher nor anyRequest — order can't be trusted
                i++;
            }
        }
        if (rules.stream().noneMatch(SecurityRule::anyRequest)) {
            ambiguous = true;   // Spring 6 requires an anyRequest() default; without it our view is incomplete
        }
        return new SecurityChain(rules, ambiguous);
    }

    private static boolean isMatcher(String name) {
        return name.equals("requestMatchers") || name.equals("antMatchers") || name.equals("mvcMatchers");
    }

    /** Parse {@code requestMatchers(HttpMethod.X?, "literal"...)} — null if any argument is not a string/method literal. */
    static Matcher parseMatcher(MethodCallExpr call) {
        List<Expression> args = call.getArguments();
        if (args.isEmpty()) {
            return null;
        }
        Set<HttpMethod> methods = Set.of();
        int start = 0;
        if (args.get(0) instanceof FieldAccessExpr fa && "HttpMethod".equals(fa.getScope().toString())) {
            HttpMethod hm = httpMethod(fa.getNameAsString());
            if (hm == null) {
                return null;
            }
            methods = Set.of(hm);
            start = 1;
        }
        List<String> patterns = new ArrayList<>();
        for (int k = start; k < args.size(); k++) {
            if (args.get(k) instanceof StringLiteralExpr sl) {
                patterns.add(sl.asString());
            } else {
                return null;   // a non-literal pattern (constant ref / variable / expression) → ambiguous
            }
        }
        return patterns.isEmpty() ? null : new Matcher(methods, patterns);
    }

    static SecurityRule.Access accessOf(String term) {
        return switch (term) {
            case "permitAll" -> SecurityRule.Access.PERMIT_ALL;
            case "authenticated", "fullyAuthenticated" -> SecurityRule.Access.AUTHENTICATED;
            case "denyAll" -> SecurityRule.Access.DENY_ALL;
            case "hasRole", "hasAnyRole" -> SecurityRule.Access.ROLE;
            case "hasAuthority", "hasAnyAuthority" -> SecurityRule.Access.AUTHORITY;
            default -> SecurityRule.Access.UNKNOWN;   // access(...), anonymous, rememberMe, … — don't guess
        };
    }

    static List<String> literalArgs(MethodCallExpr term) {
        List<String> out = new ArrayList<>();
        for (Expression a : term.getArguments()) {
            if (a instanceof StringLiteralExpr sl) {
                out.add(sl.asString());
            }
        }
        return out;
    }

    /**
     * Resolve an endpoint's security from the chain by Spring FIRST-MATCH, but only UNAMBIGUOUSLY: returns the
     * matched rule when exactly one specific (non-default) matcher applies (method + simple Ant pattern) or none does
     * and the explicit {@code anyRequest()} default decides it; returns {@code null} (→ keep the blind spot) when the
     * chain is ambiguous, two specific matchers overlap this endpoint, or a non-simple pattern could match it.
     */
    static SecurityRule resolveEndpointSecurity(Endpoint e, SecurityChain chain) {
        if (chain.ambiguous()) {
            return null;
        }
        List<SecurityRule> applicable = new ArrayList<>();
        SecurityRule anyRequest = null;
        for (SecurityRule r : chain.rules()) {
            if (r.anyRequest()) {
                anyRequest = r;
                continue;
            }
            boolean methodOk = r.methods().isEmpty() || r.methods().contains(e.method());
            if (!methodOk) {
                continue;   // a method-specific rule that doesn't cover this endpoint's verb can't apply
            }
            if (!isSimplePattern(r.pattern())) {
                if (ANT.match(r.pattern(), e.pathTemplate())) {
                    return null;   // a wildcard pattern we don't fully trust could match this endpoint → ambiguous
                }
                continue;
            }
            if (ANT.match(r.pattern(), e.pathTemplate())) {
                applicable.add(r);
            } else if (ANT.match(stripTrailingSlash(r.pattern()), stripTrailingSlash(e.pathTemplate()))) {
                // A near miss — a specific rule that matches only after normalising a trailing slash (the Spring
                // Boot 3 trailing-slash footgun). Whether it governs this endpoint at runtime is genuinely unclear,
                // so decline rather than (wrongly) fall through to the anyRequest default and fabricate permitAll.
                return null;
            }
        }
        if (applicable.size() > 1) {
            return null;   // overlapping specific matchers → which wins is too subtle; keep the blind spot
        }
        return applicable.size() == 1 ? applicable.get(0) : anyRequest;
    }

    /** Strip a single trailing slash (but never from the root "/") for near-miss detection. */
    static String stripTrailingSlash(String p) {
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** A pattern safe to match deterministically: an exact literal, or a single trailing {@code /**} prefix. */
    static boolean isSimplePattern(String p) {
        if (p == null || p.isEmpty()) {
            return false;
        }
        String core = p.endsWith("/**") ? p.substring(0, p.length() - 3) : p;
        return !core.contains("*") && !core.contains("?");
    }

    /** The endpoint-security strings for a resolved rule, in the same expression shape annotation security produces. */
    static List<String> securityExpr(SecurityRule r) {
        return switch (r.access()) {
            case AUTHENTICATED -> List.of("authenticated");
            case DENY_ALL -> List.of("denyAll");
            case ROLE -> List.of(roleExpr("hasRole", "hasAnyRole", r.roles()));
            case AUTHORITY -> List.of(roleExpr("hasAuthority", "hasAnyAuthority", r.roles()));
            default -> List.of();   // PERMIT_ALL / UNKNOWN — not reached for a secured rule
        };
    }

    static String roleExpr(String single, String multi, List<String> roles) {
        if (roles.isEmpty()) {
            return single + "()";
        }
        if (roles.size() == 1) {
            return single + "('" + roles.get(0) + "')";
        }
        return multi + "(" + roles.stream().map(x -> "'" + x + "'")
                .collect(Collectors.joining(", ")) + ")";
    }

    static HttpMethod httpMethod(String name) {
        try {
            return HttpMethod.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Required roles/scopes from security annotations (@PreAuthorize / @Secured / @RolesAllowed) — including custom
     * annotations meta-annotated with one of them (e.g. a project @AdminOnly). Missing the meta path would report a
     * protected endpoint as UNSECURED — a security false-negative, the worst direction of error in a bank context.
     */
    static List<String> securityOf(NodeWithAnnotations<?> n, Map<String, TypeDeclaration<?>> types) {
        List<String> out = new ArrayList<>();
        addSecurity(n, out);   // literal @PreAuthorize/@Secured/@RolesAllowed/@PermitAll/@DenyAll on the node itself
        for (AnnotationExpr a : n.getAnnotations()) {
            String name = a.getNameAsString();
            if (types.get(name) instanceof AnnotationDeclaration decl) {
                addSecurity(decl, out);   // composed/meta annotation declared in the scanned sources
            } else if (types.get(name) == null && looksLikeSecurityAnnotation(name)) {
                // A security-SUGGESTIVE annotation that can't be resolved from the scanned sources (e.g. @AdminOnly in a
                // shared security jar) likely imposes authorization we cannot read — treat the endpoint as SECURED with
                // an honest "unresolved" token rather than silently reading it as OPEN (a security false-negative).
                out.add("unresolved:@" + name);
            }
        }
        // Dedup: @PreAuthorize + @PostAuthorize with the same SpEL (or a role repeated across @Secured/@RolesAllowed)
        // would otherwise emit the same token twice, duplicating it in the SECURITY_MISMATCH message and finding id.
        return out.stream().distinct().toList();
    }

    private static void addSecurity(NodeWithAnnotations<?> n, List<String> out) {
        // A @PreAuthorize whose SpEL is an OPEN sentinel (permitAll/isAnonymous) is not an authorization constraint —
        // mirror the SecurityFilterChain path's PERMIT_ALL exclusion; otherwise an explicitly-open endpoint fabricates a
        // false (CRITICAL) SECURITY_MISMATCH. @Secured/@RolesAllowed anonymous sentinels mean OPEN too, so drop them.
        getAnnotation(n, "PreAuthorize").map(a -> firstString(a, "value"))
                .filter(SecuritySupport::isSecuringSpel).ifPresent(out::add);
        // @PostAuthorize enforces authorization POST-invocation (throws AccessDeniedException) — it secures the endpoint
        // exactly like @PreAuthorize. Omitting it read a @PostAuthorize-only endpoint as UNSECURED (a security
        // false-negative + a fabricated SECURITY_MISMATCH against a secured spec).
        getAnnotation(n, "PostAuthorize").map(a -> firstString(a, "value"))
                .filter(SecuritySupport::isSecuringSpel).ifPresent(out::add);
        getAnnotation(n, "Secured").ifPresent(a -> out.addAll(securingRoles(stringValues(a, "value"))));
        getAnnotation(n, "RolesAllowed").ifPresent(a -> out.addAll(securingRoles(stringValues(a, "value"))));
        // JSR-250: @DenyAll LOCKS the endpoint → a securing token; @PermitAll OPENS it → contributes nothing.
        if (has(n, "DenyAll")) {
            out.add("denyAll");
        }
    }

    /** OPEN-access SpEL sentinels that do NOT constrain access (normalized: lower-cased, parens/space stripped).
     *  The literal {@code true} always permits → OPEN, the boolean counterpart of permitAll().
     *  NOTE: denyAll()/false are deliberately NOT here — they LOCK the endpoint (403 to everyone), the OPPOSITE of open;
     *  treating them as open would read a locked endpoint as unsecured (a security false-negative). They stay as
     *  securing tokens so a denyAll-vs-spec-open divergence is still reported. */
    private static final Set<String> NON_SECURING_SPEL = Set.of(
            "permitall", "isanonymous", "anonymous", "true");

    /** @Secured/@RolesAllowed anonymous sentinels — they grant anonymous access (OPEN), not a real role. */
    private static final Set<String> ANONYMOUS_ROLES = Set.of(
            "is_authenticated_anonymously", "role_anonymous", "anonymous");

    /** Drop the anonymous sentinels from a role list — what's left is the real authorization constraint (possibly none). */
    private static List<String> securingRoles(List<String> roles) {
        return roles.stream()
                .filter(r -> r != null && !ANONYMOUS_ROLES.contains(r.trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** True when a @PreAuthorize SpEL actually constrains access (hasRole/hasAuthority/isAuthenticated/denyAll/...). */
    private static boolean isSecuringSpel(String spel) {
        if (spel == null) {
            return false;
        }
        String s = spel.trim().toLowerCase(Locale.ROOT).replace("()", "").replace(" ", "");
        return !NON_SECURING_SPEL.contains(s);
    }

    /** The Spring/JSR-250 method-security annotations the extractor reads directly (so an UNRESOLVED look-alike isn't
     *  double-counted as an "unresolved" custom annotation). */
    private static final Set<String> KNOWN_SECURITY_ANNOTATIONS = Set.of(
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PermitAll", "DenyAll", "PreFilter", "PostFilter");

    /** OpenAPI/swagger DOCUMENTATION annotations (io.swagger.v3.oas.annotations.security.*) that contain a security
     *  token in their name but impose ZERO runtime authorization — they describe the spec, not enforce access. They are
     *  pervasive on controllers in springdoc projects (e.g. {@code @SecurityRequirement(name="bearerAuth")}); treating
     *  one as "secured" fabricates a false CRITICAL SECURITY_MISMATCH against a spec that declares no operation security. */
    private static final Set<String> OPENAPI_DOC_SECURITY_ANNOTATIONS = Set.of(
            "SecurityRequirement", "SecurityRequirements", "SecurityScheme", "SecuritySchemes");

    /** A custom annotation whose simple name SUGGESTS it imposes authorization (so an unresolvable one is treated as
     *  secured-unknown rather than open). Excludes the framework annotations already read directly and the swagger
     *  documentation annotations that merely describe (not enforce) security. */
    private static boolean looksLikeSecurityAnnotation(String name) {
        if (name == null || KNOWN_SECURITY_ANNOTATIONS.contains(name)
                || OPENAPI_DOC_SECURITY_ANNOTATIONS.contains(name)) {
            return false;
        }
        // STRONG tokens only — broad ones (auth→@Author, role→@Role) would false-flag benign annotations as secured.
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("secur") || n.contains("authoriz") || n.contains("rolesallowed")
                || n.contains("preauth") || n.contains("postauth") || n.contains("permission") || n.contains("admin");
    }

    /** True when the node carries any (literal, composed, or unresolved-security-suggestive) method-security annotation
     *  — so it OVERRIDES the class default even when its resolved expression is open (method @PermitAll on a secured
     *  class) or unknown. */
    static boolean hasSecurityAnnotation(NodeWithAnnotations<?> n, Map<String, TypeDeclaration<?>> types) {
        if (has(n, "PreAuthorize") || has(n, "PostAuthorize") || has(n, "Secured") || has(n, "RolesAllowed")
                || has(n, "PermitAll") || has(n, "DenyAll")) {
            return true;
        }
        for (AnnotationExpr a : n.getAnnotations()) {
            String name = a.getNameAsString();
            if (types.get(name) instanceof AnnotationDeclaration decl
                    && (has(decl, "PreAuthorize") || has(decl, "PostAuthorize") || has(decl, "Secured")
                        || has(decl, "RolesAllowed") || has(decl, "PermitAll") || has(decl, "DenyAll"))) {
                return true;
            }
            if (types.get(name) == null && looksLikeSecurityAnnotation(name)) {
                return true;
            }
        }
        return false;
    }
}
