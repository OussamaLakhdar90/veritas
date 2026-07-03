package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conservative SecurityFilterChain resolution: turn the all-or-nothing "centralized security" blind spot into
 * per-endpoint security WHERE the {@code authorizeHttpRequests} chain matches an endpoint unambiguously (Spring
 * first-match), and KEEP the blind spot for anything ambiguous — never fabricating security or hiding a real gap.
 */
class SecurityFilterChainExtractTest {

    private static final String CTRL_HDR = "package demo;\nimport org.springframework.web.bind.annotation.*;\n";
    private static final String SEC_HDR = "package demo;\n"
            + "import org.springframework.security.web.SecurityFilterChain;\n"
            + "import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n"
            + "import org.springframework.http.HttpMethod;\n";

    private List<String> security(ApiModel m, String method, String path) {
        return m.endpoints().stream()
                .filter(e -> e.pathTemplate().equals(path) && e.method() == HttpMethod.valueOf(method))
                .map(Endpoint::security).findFirst().orElseThrow();
    }

    private boolean keptBlindSpot(ApiModel m) {
        return m.blindSpots().stream().anyMatch(b -> b.contains("SecurityFilterChain"));
    }

    private void writeChain(Path dir, String authorizeBody) throws Exception {
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR
                + "class SecurityConfig {\n"
                + "  static final String CONST_PATH = \"/x\";\n"
                + "  static final String CONST_ROLE = \"ADMIN\";\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(auth -> auth" + authorizeBody + ").build();\n"
                + "  }\n}\n");
    }

    @Test
    void exactAndPrefixRulesResolveAndLeaveNoBlindSpotWhenWholeChainResolves(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/api/admin\") String admin(){return null;}\n"
                + "  @GetMapping(\"/admin/users\") String users(){return null;}\n"
                + "  @GetMapping(\"/api/public\") String pub(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/api/admin\").hasRole(\"ADMIN\")"
                + ".requestMatchers(\"/admin/**\").authenticated()"
                + ".anyRequest().permitAll()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/api/admin")).anyMatch(s -> s.contains("ADMIN"));   // exact-path hasRole
        assertThat(security(m, "GET", "/admin/users")).containsExactly("authenticated");   // /admin/** prefix
        assertThat(security(m, "GET", "/api/public")).isEmpty();                           // anyRequest permitAll
        assertThat(keptBlindSpot(m)).isFalse();   // every endpoint resolved → no suppression needed
    }

    @Test
    void methodSpecificMatcherIsHonored(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/x\") String g(){return null;}\n"
                + "  @PostMapping(\"/x\") String p(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(HttpMethod.POST, \"/x\").authenticated().anyRequest().permitAll()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/x")).isEmpty();                       // GET falls through to permitAll
        assertThat(security(m, "POST", "/x")).containsExactly("authenticated");   // POST matches the method rule
        assertThat(keptBlindSpot(m)).isFalse();
    }

    @Test
    void overlappingSpecificMatchersAreAmbiguousAndKeepTheBlindSpot(@TempDir Path dir) throws Exception {
        // /admin/secret is matched by BOTH /admin/** and /admin/secret — which wins is too subtle, so keep the
        // blind spot rather than guess (and DiffEngine keeps suppressing a false UNSECURED there).
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/admin/secret\") String s(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/admin/**\").permitAll()"
                + ".requestMatchers(\"/admin/secret\").authenticated()"
                + ".anyRequest().permitAll()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/admin/secret")).isEmpty();   // not resolved
        assertThat(keptBlindSpot(m)).isTrue();
    }

    @Test
    void aNonLiteralMatcherMakesTheChainAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/x\") String g(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(CONST_PATH).permitAll().anyRequest().permitAll()");   // constant, not a literal

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/x")).isEmpty();   // nothing fabricated
        assertThat(keptBlindSpot(m)).isTrue();
    }

    @Test
    void anUnrecognisedAuthorizeTerminalMakesTheChainAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/x\") String g(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/x\").access(custom).anyRequest().permitAll()");   // .access(...) → UNKNOWN

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/x")).isEmpty();
        assertThat(keptBlindSpot(m)).isTrue();
    }

    @Test
    void aChainWithoutAnyRequestDefaultIsAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/x\") String g(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/x\").authenticated()");   // no anyRequest() default

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/x")).isEmpty();
        assertThat(keptBlindSpot(m)).isTrue();
    }

    @Test
    void annotationSecurityIsNotOverriddenByTheChain(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR
                + "import org.springframework.security.access.prepost.PreAuthorize;\n"
                + "@RestController class Ctrl {\n"
                + "  @PreAuthorize(\"hasRole('OWNER')\") @GetMapping(\"/x\") String g(){return null;}\n}\n");
        writeChain(dir, ".anyRequest().permitAll()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/x")).anyMatch(s -> s.contains("OWNER"));   // the annotation wins, not permitAll
    }

    @Test
    void aNonLiteralRoleArgumentMakesTheChainAmbiguous(@TempDir Path dir) throws Exception {
        // hasRole(CONST) — the role is a constant, not a string literal. Resolving it would fabricate an empty
        // hasRole() and (worse) drop the blind spot; instead the chain must stay ambiguous.
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/admin/users\") String u(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/admin/**\").hasRole(CONST_ROLE).anyRequest().permitAll()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/admin/users")).isEmpty();   // no fabricated hasRole()
        assertThat(keptBlindSpot(m)).isTrue();
    }

    @Test
    void mixedLiteralAndConstantRoleArgsMakeTheChainAmbiguous(@TempDir Path dir) throws Exception {
        // hasAnyRole("ADMIN", CONST_ROLE) — one literal, one constant. Emitting just ["ADMIN"] would understate the
        // enforced roles, so the chain declines entirely.
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/ops/jobs\") String j(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/ops/**\").hasAnyRole(\"OPS\", CONST_ROLE).anyRequest().permitAll()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/ops/jobs")).isEmpty();
        assertThat(keptBlindSpot(m)).isTrue();
    }

    @Test
    void denyAllRouteKeepsTheBlindSpotAndDoesNotFabricateAnAuthorizationMismatch(@TempDir Path dir) throws Exception {
        // denyAll is a 403-to-everyone route — neither "secured" nor "unsecured" in the spec's model. It must NOT be
        // reported as "Code enforces authorization (denyAll)" against a spec that merely omits security.
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/legacy/foo\") String f(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/legacy/**\").denyAll().anyRequest().permitAll()");

        ApiModel code = new JavaSpringExtractor().extract(dir);
        SourceRef src = SourceRef.code("openapi.yaml", 1, 1, "spec");
        ApiModel spec = new ApiModel("spec", null, null, null,
                List.of(new Endpoint(HttpMethod.GET, "/legacy/foo", "f", List.of(), null,
                        List.of(new ResponseModel(200, null, null, "SPEC", src)),
                        List.of(), List.of(), List.of(), src)),   // spec declares NO security
                java.util.Map.of());

        assertThat(security(code, "GET", "/legacy/foo")).isEmpty();   // not modelled as authorization
        assertThat(keptBlindSpot(code)).isTrue();
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);   // no fabricated mismatch
    }

    @Test
    void aTrailingSlashNearMissKeepsTheBlindSpotInsteadOfFabricatingPermitAll(@TempDir Path dir) throws Exception {
        // The chain pattern "/logout/" differs from the endpoint path "/logout" only by a trailing slash — Spring
        // Boot 3's trailing-slash behaviour makes it genuinely unclear whether the rule governs. Decline, don't
        // silently fall through to anyRequest().permitAll() and fire a false SECURITY_MISMATCH against a secured spec.
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/logout\") String l(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/logout/\").authenticated().anyRequest().permitAll()");

        ApiModel code = new JavaSpringExtractor().extract(dir);
        SourceRef src = SourceRef.code("openapi.yaml", 1, 1, "spec");
        ApiModel spec = new ApiModel("spec", null, null, null,
                List.of(new Endpoint(HttpMethod.GET, "/logout", "l", List.of(), null,
                        List.of(new ResponseModel(200, null, null, "SPEC", src)),
                        List.of(), List.of(), List.of("bearerAuth"), src)),
                java.util.Map.of());

        assertThat(security(code, "GET", "/logout")).isEmpty();
        assertThat(keptBlindSpot(code)).isTrue();   // blind spot kept → DiffEngine suppresses, no false mismatch
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);
    }

    @Test
    void anyRequestBeforeALaterMatcherIsAmbiguous(@TempDir Path dir) throws Exception {
        // .anyRequest().permitAll().requestMatchers("/admin").authenticated() is a config Spring rejects at boot —
        // don't model it as a clean rule set (which would resolve /admin and drop the blind spot).
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/admin\") String a(){return null;}\n}\n");
        writeChain(dir, ".anyRequest().permitAll().requestMatchers(\"/admin\").authenticated()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/admin")).isEmpty();
        assertThat(keptBlindSpot(m)).isTrue();
    }

    @Test
    void contextPathIsStrippedBeforeMatchingSoPermitAllPrefixResolves(@TempDir Path dir) throws Exception {
        // S13j-1: with server.servlet.context-path=/ciam the endpoint's pathTemplate is /ciam/public/health, but Spring
        // Security Ant-matches its chain against the CONTEXT-RELATIVE path (/public/health). Matching permitAll("/public
        // /**") against the base-prefixed path would miss and fabricate a false CRITICAL SECURITY_MISMATCH.
        Files.writeString(dir.resolve("application.yml"), "server:\n  servlet:\n    context-path: /ciam\n");
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/public/health\") String h(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/public/**\").permitAll().anyRequest().authenticated()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/ciam/public/health")).isEmpty();   // permit-all resolves via the relative path
        assertThat(keptBlindSpot(m)).isFalse();
    }

    @Test
    void contextPathIsStrippedSoAnAuthenticatedPrefixStillResolves(@TempDir Path dir) throws Exception {
        // Inverse of the above: the chain secures /api/** and permits everything else. Without stripping /ciam the
        // /ciam/api/x endpoint would silently fall through to anyRequest().permitAll() — a false NEGATIVE.
        Files.writeString(dir.resolve("application.yml"), "server:\n  servlet:\n    context-path: /ciam\n");
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/api/x\") String x(){return null;}\n}\n");
        writeChain(dir, ".requestMatchers(\"/api/**\").authenticated().anyRequest().permitAll()");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(security(m, "GET", "/ciam/api/x")).containsExactly("authenticated");
        assertThat(keptBlindSpot(m)).isFalse();
    }

    @Test
    void permitAllUnderAGlobalSecuritySpecNowFiresSecurityMismatch(@TempDir Path dir) throws Exception {
        // The previously-hidden security false-negative: the chain leaves /x wide open while the spec requires auth.
        Files.writeString(dir.resolve("Ctrl.java"), CTRL_HDR + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/x\") String g(){return null;}\n}\n");
        writeChain(dir, ".anyRequest().permitAll()");

        ApiModel code = new JavaSpringExtractor().extract(dir);
        SourceRef src = SourceRef.code("openapi.yaml", 1, 1, "spec");
        ApiModel spec = new ApiModel("spec", null, null, null,
                List.of(new Endpoint(HttpMethod.GET, "/x", "g", List.of(), null,
                        List.of(new ResponseModel(200, null, null, "SPEC", src)),
                        List.of(), List.of(), List.of("bearerAuth"), src)),
                java.util.Map.of());

        List<Finding> findings = new DiffEngine().diffCodeVsSpec(code, spec);

        assertThat(findings).anyMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH
                && f.getSummary().contains("Spec requires security"));
        assertThat(keptBlindSpot(code)).isFalse();   // resolved permitAll → no suppression → the gap surfaces
    }
}
