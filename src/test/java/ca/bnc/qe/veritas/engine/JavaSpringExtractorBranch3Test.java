package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-3 branch maximiser for {@link JavaSpringExtractor}: targets the deepest red/yellow edges the earlier
 * suites (JavaSpringExtractorBranchTest / Branch2Test / SecurityFilterChainExtractTest / SolverAudit / Robustness /
 * Depth / ResponseEnvelope / BlindSpot) leave uncovered — none of these scenarios are exercised elsewhere:
 *
 * <ul>
 *   <li>SecurityFilterChain bean with NO authorize DSL at all (parseSecurityChain returns null, the centralized
 *       blind spot is then kept via usesCentralizedSecurity);</li>
 *   <li>a chain scoped by {@code securityMatcher(...)}, an authorize argument that is a Customizer object rather
 *       than a lambda, an empty {@code requestMatchers()}, an unknown {@code HttpMethod} literal, a stray fluent
 *       call that is neither matcher nor anyRequest, and a dangling matcher with no following terminal;</li>
 *   <li>the symbol-solver fallback returning TRUE for a JDK-resolvable referenced type (no blind spot);</li>
 *   <li>schema-recursion guards: a self-referential and a mutually-referential inheritance cycle;</li>
 *   <li>a {@code List<? super Foo>} (wildcard SUPER bound) and a bare {@code List<?>} (Object fallback);</li>
 *   <li>an unmapped {@code HttpStatus} and an unknown {@code ResponseEntity} factory falling back to 200;</li>
 *   <li>a method-call path expression and a partially-unresolvable path concatenation → honest blind spots.</li>
 * </ul>
 *
 * <p>Every assertion checks concrete extracted values; order-independent only. Production code is NOT modified
 * and no existing test file is touched.
 */
class JavaSpringExtractorBranch3Test {

    private static final String WEB = "package demo;\nimport org.springframework.web.bind.annotation.*;\n";
    private static final String SEC_HDR = "package demo;\n"
            + "import org.springframework.security.web.SecurityFilterChain;\n"
            + "import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n"
            + "import org.springframework.http.HttpMethod;\n";

    private static ApiModel extract(Path dir) {
        return new JavaSpringExtractor().extract(dir);
    }

    private List<String> securityOf(ApiModel m, String method, String path) {
        return m.endpoints().stream()
                .filter(e -> e.pathTemplate().equals(path) && e.method() == HttpMethod.valueOf(method))
                .map(Endpoint::security).findFirst().orElseThrow();
    }

    private boolean keptSecurityBlindSpot(ApiModel m) {
        return m.blindSpots().stream().anyMatch(b -> b.contains("SecurityFilterChain"));
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).isNotEmpty();
        return m.endpoints().get(0);
    }

    // ---------------------------------------------------------------------------------------------------------
    // SecurityFilterChain: rare DSL shapes (each must keep the coarse "centralized security" blind spot).
    // ---------------------------------------------------------------------------------------------------------

    /** A SecurityFilterChain bean exists but declares no authorizeHttpRequests/authorizeRequests DSL we read. */
    @Test
    void securityFilterChainBeanWithoutAuthorizeDslKeepsTheBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/x\") String g(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR + "class SecurityConfig {\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.csrf(c -> c.disable()).build();\n"   // no authorize DSL at all
                + "  }\n}\n");

        ApiModel m = extract(dir);

        assertThat(securityOf(m, "GET", "/x")).isEmpty();   // nothing resolved / fabricated
        assertThat(keptSecurityBlindSpot(m)).isTrue();      // SecurityFilterChain type present → blind spot kept
    }

    /** A chain scoped to a URL subset via securityMatcher(...) is out of conservative scope → ambiguous. */
    @Test
    void securityMatcherScopedChainIsAmbiguousAndKeepsTheBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/api/x\") String g(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR + "class SecurityConfig {\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.securityMatcher(\"/api/**\")\n"
                + "        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();\n"
                + "  }\n}\n");

        ApiModel m = extract(dir);

        assertThat(securityOf(m, "GET", "/api/x")).isEmpty();
        assertThat(keptSecurityBlindSpot(m)).isTrue();
    }

    /** authorizeHttpRequests given a Customizer object / method-ref instead of a lambda → ambiguous. */
    @Test
    void authorizeHttpRequestsWithNonLambdaArgumentIsAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/x\") String g(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR
                + "import org.springframework.security.config.Customizer;\n"
                + "class SecurityConfig {\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(Customizer.withDefaults()).build();\n"   // not a lambda
                + "  }\n}\n");

        ApiModel m = extract(dir);

        assertThat(securityOf(m, "GET", "/x")).isEmpty();
        assertThat(keptSecurityBlindSpot(m)).isTrue();
    }

    /** requestMatchers() with no arguments cannot be parsed to a literal pattern → ambiguous. */
    @Test
    void emptyRequestMatchersArgsMakeTheChainAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/x\") String g(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR + "class SecurityConfig {\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(auth -> auth\n"
                + "        .requestMatchers().authenticated().anyRequest().permitAll()).build();\n"
                + "  }\n}\n");

        ApiModel m = extract(dir);

        assertThat(securityOf(m, "GET", "/x")).isEmpty();
        assertThat(keptSecurityBlindSpot(m)).isTrue();
    }

    /** requestMatchers(HttpMethod.<bogus>, "/x") — an unknown HTTP method literal makes the matcher unparseable. */
    @Test
    void requestMatchersWithUnknownHttpMethodIsAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/x\") String g(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR + "class SecurityConfig {\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(auth -> auth\n"
                + "        .requestMatchers(HttpMethod.BREW, \"/x\").authenticated().anyRequest().permitAll()).build();\n"
                + "  }\n}\n");

        ApiModel m = extract(dir);

        assertThat(securityOf(m, "GET", "/x")).isEmpty();
        assertThat(keptSecurityBlindSpot(m)).isTrue();
    }

    /** A stray fluent call in the chain that is neither a matcher nor anyRequest() makes the order untrustworthy. */
    @Test
    void strayNonMatcherChainCallIsAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/x\") String g(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR + "class SecurityConfig {\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(auth -> auth\n"
                + "        .shrug().anyRequest().permitAll()).build();\n"   // 'shrug' is neither matcher nor anyRequest
                + "  }\n}\n");

        ApiModel m = extract(dir);

        assertThat(securityOf(m, "GET", "/x")).isEmpty();
        assertThat(keptSecurityBlindSpot(m)).isTrue();
    }

    /** A trailing matcher with no following terminal (chain ends on the matcher) → ambiguous, blind spot kept. */
    @Test
    void matcherWithoutFollowingTerminalIsAmbiguous(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/x\") String g(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR + "class SecurityConfig {\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(auth -> auth\n"
                + "        .anyRequest().permitAll().requestMatchers(\"/x\")).build();\n"   // dangling matcher, no terminal
                + "  }\n}\n");

        ApiModel m = extract(dir);

        assertThat(securityOf(m, "GET", "/x")).isEmpty();
        assertThat(keptSecurityBlindSpot(m)).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Symbol-solver fallback: a JDK-resolvable referenced type must NOT be flagged as an unresolved blind spot.
    // ---------------------------------------------------------------------------------------------------------

    /** java.time.Duration is not a scanned DTO nor an IR scalar, but the symbol solver resolves it → no blind spot. */
    @Test
    void jdkResolvableReferencedTypeIsNotFlaggedAsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB + "import java.time.Duration;\n"
                + "@RestController class Ctrl { @GetMapping(\"/d\") Duration g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.blindSpots().stream().anyMatch(b -> b.contains("Duration"))).isFalse();
        assertThat(m.schemas()).doesNotContainKey("Duration");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Schema recursion / inheritance guards.
    // ---------------------------------------------------------------------------------------------------------

    /** A DTO that (illegally) extends itself must not loop forever — the visited guard stops the recursion. */
    @Test
    void selfReferentialInheritanceDoesNotLoop(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Loop.java"),
                "package demo; public class Loop extends Loop { public String id; }");
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/l\") Loop g(){return null;} }");

        SchemaModel s = extract(dir).schemas().get("Loop");

        assertThat(s).isNotNull();
        assertThat(s.fields()).extracting("jsonName").containsExactly("id");   // single own field, no duplication
    }

    /** Mutually-inheriting DTOs (A extends B, B extends A) must terminate via the visited-type guard. */
    @Test
    void mutualInheritanceTerminatesViaVisitedGuard(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Aaa.java"),
                "package demo; public class Aaa extends Bbb { public String a; }");
        Files.writeString(dir.resolve("Bbb.java"),
                "package demo; public class Bbb extends Aaa { public String b; }");
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/a\") Aaa g(){return null;} }");

        SchemaModel s = extract(dir).schemas().get("Aaa");

        assertThat(s).isNotNull();
        assertThat(s.fields()).extracting("jsonName").contains("a", "b");   // both fields collected
        assertThat(s.fields()).hasSize(2);                                  // each exactly once (no cycle blow-up)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Wildcard type-bound recovery.
    // ---------------------------------------------------------------------------------------------------------

    /** A wildcard with a LOWER bound (List<? super Foo>) recovers the super-bound type, not the literal "? super". */
    @Test
    void wildcardSuperBoundRecoversBoundType(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Foo.java"), "package demo; public class Foo { public String id; }");
        Files.writeString(dir.resolve("Ctrl.java"), WEB + "import java.util.List;\n"
                + "@RestController class Ctrl { @GetMapping(\"/w\") List<? super Foo> g(){return null;} }");

        ApiModel m = extract(dir);

        // List<? super Foo> → array of Foo (the lower bound is recovered), schema built.
        assertThat(only(m).responses().get(0).schemaRef()).isEqualTo("Foo[]");
        assertThat(m.schemas()).containsKey("Foo");
    }

    /**
     * An unbounded wildcard (List<?>) has neither an extends nor a super bound, so simpleTypeName falls back to
     * "Object" (the orElse arm). The body is therefore an array of Object — Object is not a scanned DTO so no
     * schema is built for it, and the literal "?" is never leaked as a blind spot.
     */
    @Test
    void unboundedWildcardRecoversObjectAsArray(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB + "import java.util.List;\n"
                + "@RestController class Ctrl { @GetMapping(\"/w\") List<?> g(){return null;} }");

        ApiModel m = extract(dir);

        // wildcard with no bound → Object element → "Object[]" array schemaRef (the orElse("Object") fallback).
        assertThat(only(m).responses().get(0).schemaRef()).isEqualTo("Object[]");
        assertThat(m.schemas()).doesNotContainKey("Object");        // Object is not a scanned DTO → no phantom schema
        assertThat(m.blindSpots().toString()).doesNotContain("?");  // no literal wildcard leaked as a blind spot
    }

    // ---------------------------------------------------------------------------------------------------------
    // Unusual status sources falling back to the 200 default.
    // ---------------------------------------------------------------------------------------------------------

    /** An unmapped HttpStatus (I_AM_A_TEAPOT) in ResponseEntity.status(...) resolves to no code → 200 default. */
    @Test
    void unmappedHttpStatusFallsBackToDefaultSuccess(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "import org.springframework.http.ResponseEntity;\n"
                + "import org.springframework.http.HttpStatus;\n"
                + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/teapot\") ResponseEntity<String> t(){\n"
                + "    return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).body(\"x\");\n"
                + "  }\n}\n");

        Endpoint e = only(extract(dir));

        // The teapot status is not in statusFromText's map → no entityStatuses → the endpoint defaults to 200.
        assertThat(e.responses()).extracting(ResponseModel::statusCode).containsExactly(200);
    }

    /** An unknown ResponseEntity factory (e.g. .ok() with only a header builder) yields no recognised status → 200. */
    @Test
    void unknownResponseEntityFactoryYieldsNoExtraStatus(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "import org.springframework.http.ResponseEntity;\n"
                + "@RestController class Ctrl {\n"
                + "  @GetMapping(\"/h\") ResponseEntity<String> h(){\n"
                + "    return ResponseEntity.ok().header(\"X-A\", \"1\").build();\n"   // 'header' → default null arm
                + "  }\n}\n");

        Endpoint e = only(extract(dir));

        // 'ok' maps to 200; 'header'/'build' fall through the factory switch's default → only 200 emitted.
        assertThat(e.responses()).extracting(ResponseModel::statusCode).containsExactly(200);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Path-expression resolution failures → honest blind spots.
    // ---------------------------------------------------------------------------------------------------------

    /** A path expression that is a method call (neither literal, constant, nor concatenation) → blind spot. */
    @Test
    void methodCallPathExpressionRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(buildPath()) String g(){return null;}\n"
                + "  static String buildPath(){ return \"/x\"; } }");

        ApiModel m = extract(dir);

        assertThat(m.blindSpots().stream().anyMatch(b -> b.contains("could not be resolved to a literal"))).isTrue();
    }

    /** A binary concatenation with one unresolvable operand cannot resolve as a whole → blind spot. */
    @Test
    void partiallyUnresolvableConcatenationRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), WEB
                + "@RestController class Ctrl { @GetMapping(\"/base\" + unknownVar) String g(){return null;} }");

        ApiModel m = extract(dir);

        assertThat(m.blindSpots().stream().anyMatch(b -> b.contains("could not be resolved to a literal"))).isTrue();
    }
}
