package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-2 branch-coverage companion for
 * {@link ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor} — targets the red ("nc"/"bnc") and yellow
 * ("pc"/"bpc") lines/branches the existing siblings (JavaSpringExtractorBranchTest, ...SolverAuditTest,
 * SecurityFilterChainExtractTest, ...Robustness/Depth/ResponseEnvelope/BlindSpot) leave uncovered:
 * the AUTHORITY/denyAll/multi-role security-expression arms, block-statement authorize lambdas, the
 * authorizeRequests/antMatchers/mvcMatchers matcher aliases, advice media-type resolution
 * (MediaType.X / parseMediaType / valueOf / *_VALUE / string-literal), the remaining framework-exception and
 * ResponseEntity-factory status maps, every HttpStatus-name arm of statusFromText, @ResponseStatus OK/ACCEPTED,
 * @ExceptionHandler value-array + parameter-derived exception names, ProblemDetail / new ResponseEntity error
 * statuses, the openApiType scalar arms (UUID / boolean / LocalDate / LocalDateTime), array-form annotation
 * literals, and unresolved constant / empty-method-array path edges.
 *
 * <p>Each test writes a tiny @TempDir Spring source fixture, runs the real extractor, and asserts concrete
 * values — never iteration/map order for a tie. Production code is NOT modified; existing tests are NOT touched.
 */
class JavaSpringExtractorBranch2Test {

    private static final String HDR = "package demo;\nimport org.springframework.web.bind.annotation.*;\n";
    private static final String SEC_HDR = "package demo;\n"
            + "import org.springframework.security.web.SecurityFilterChain;\n"
            + "import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n"
            + "import org.springframework.http.HttpMethod;\n"
            + "import org.springframework.web.bind.annotation.*;\n";

    // ---------- helpers ----------

    private static ApiModel extract(Path dir) {
        return new JavaSpringExtractor().extract(dir);
    }

    private static Endpoint ep(ApiModel m, String method, String path) {
        return m.endpoints().stream()
                .filter(e -> e.pathTemplate().equals(path) && e.method() == HttpMethod.valueOf(method))
                .findFirst().orElseThrow();
    }

    private static ResponseModel resp(Endpoint e, int status) {
        return e.responses().stream().filter(r -> r.statusCode() == status).findFirst().orElseThrow();
    }

    private static FieldModel field(SchemaModel s, String name) {
        return s.fields().stream().filter(f -> f.jsonName().equals(name)).findFirst().orElseThrow();
    }

    private static void writeChain(Path dir, String authorizeBody) throws Exception {
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR
                + "class SecurityConfig {\n"
                + "  @org.springframework.context.annotation.Bean\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(auth -> auth" + authorizeBody + ").build();\n"
                + "  }\n}\n");
    }

    // ======================================================================================
    // SecurityFilterChain: AUTHORITY / denyAll / multi-role expression arms (securityExpr + roleExpr)
    // ======================================================================================

    @Test
    void chainHasAuthoritySingleProducesHasAuthorityExpression(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/scoped/read\") String r(){return null;} }");
        writeChain(dir, ".requestMatchers(\"/scoped/**\").hasAuthority(\"SCOPE_read\").anyRequest().permitAll()");

        Endpoint e = ep(extract(dir), "GET", "/scoped/read");
        assertThat(e.security()).containsExactly("hasAuthority('SCOPE_read')");
    }

    @Test
    void chainHasAnyAuthorityMultipleProducesHasAnyAuthorityExpression(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/scoped/area\") String a(){return null;} }");
        writeChain(dir, ".requestMatchers(\"/scoped/**\").hasAnyAuthority(\"SCOPE_a\", \"SCOPE_b\")"
                + ".anyRequest().permitAll()");

        Endpoint e = ep(extract(dir), "GET", "/scoped/area");
        assertThat(e.security()).hasSize(1);
        String expr = e.security().get(0);
        assertThat(expr).startsWith("hasAnyAuthority(").contains("'SCOPE_a'").contains("'SCOPE_b'");
    }

    @Test
    void chainHasAnyRoleMultipleProducesHasAnyRoleExpression(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/mgr/area\") String a(){return null;} }");
        writeChain(dir, ".requestMatchers(\"/mgr/**\").hasAnyRole(\"ADMIN\", \"MANAGER\")"
                + ".anyRequest().permitAll()");

        Endpoint e = ep(extract(dir), "GET", "/mgr/area");
        assertThat(e.security()).hasSize(1);
        String expr = e.security().get(0);
        assertThat(expr).startsWith("hasAnyRole(").contains("'ADMIN'").contains("'MANAGER'");
    }

    @Test
    void chainDenyAllKeepsEndpointUnsecuredAndKeepsBlindSpot(@TempDir Path dir) throws Exception {
        // denyAll resolves to a rule but securityExpr(DENY_ALL) is never applied as endpoint security (it's
        // declined as ambiguous in the caller); the endpoint stays unsecured and the blind spot is kept.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/blocked/x\") String x(){return null;} }");
        writeChain(dir, ".requestMatchers(\"/blocked/**\").denyAll().anyRequest().permitAll()");

        ApiModel m = extract(dir);
        assertThat(ep(m, "GET", "/blocked/x").security()).isEmpty();
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("SecurityFilterChain"));
    }

    @Test
    void chainFullyAuthenticatedTerminalIsAuthenticated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/secure/x\") String x(){return null;} }");
        writeChain(dir, ".requestMatchers(\"/secure/**\").fullyAuthenticated().anyRequest().permitAll()");

        assertThat(ep(extract(dir), "GET", "/secure/x").security()).containsExactly("authenticated");
    }

    // ======================================================================================
    // SecurityFilterChain: lambda body shapes + matcher aliases + authorizeRequests alias
    // ======================================================================================

    @Test
    void blockStatementLambdaBodyIsParsed(@TempDir Path dir) throws Exception {
        // The authorize lambda uses a BLOCK body { auth.requestMatchers(...)...; } rather than an expression body,
        // exercising outermostChainCall's block-statement extraction branch.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/admin/area\") String a(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR
                + "class SecurityConfig {\n"
                + "  @org.springframework.context.annotation.Bean\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeHttpRequests(auth -> {\n"
                + "      auth.requestMatchers(\"/admin/**\").authenticated().anyRequest().permitAll();\n"
                + "    }).build();\n"
                + "  }\n}\n");

        assertThat(ep(extract(dir), "GET", "/admin/area").security()).containsExactly("authenticated");
    }

    @Test
    void authorizeRequestsAliasIsRecognised(@TempDir Path dir) throws Exception {
        // The legacy DSL name authorizeRequests (not authorizeHttpRequests) must still parse.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/legacy/area\") String a(){return null;} }");
        Files.writeString(dir.resolve("SecurityConfig.java"), SEC_HDR
                + "class SecurityConfig {\n"
                + "  @org.springframework.context.annotation.Bean\n"
                + "  SecurityFilterChain chain(HttpSecurity http) throws Exception {\n"
                + "    return http.authorizeRequests(auth -> auth"
                + ".requestMatchers(\"/legacy/**\").authenticated().anyRequest().permitAll()).build();\n"
                + "  }\n}\n");

        assertThat(ep(extract(dir), "GET", "/legacy/area").security()).containsExactly("authenticated");
    }

    @Test
    void antMatchersAliasIsRecognised(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/ant/area\") String a(){return null;} }");
        writeChain(dir, ".antMatchers(\"/ant/**\").authenticated().anyRequest().permitAll()");

        assertThat(ep(extract(dir), "GET", "/ant/area").security()).containsExactly("authenticated");
    }

    @Test
    void mvcMatchersAliasIsRecognised(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/mvc/area\") String a(){return null;} }");
        writeChain(dir, ".mvcMatchers(\"/mvc/**\").authenticated().anyRequest().permitAll()");

        assertThat(ep(extract(dir), "GET", "/mvc/area").security()).containsExactly("authenticated");
    }

    @Test
    void wildcardMatcherThatCouldMatchEndpointIsDeclinedAndKeepsBlindSpot(@TempDir Path dir) throws Exception {
        // A non-simple (embedded "*") pattern that ANT-matches the endpoint -> resolver returns null (ambiguous),
        // exercising the isSimplePattern==false + ANT.match==true arm (L503-505).
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/files/report.pdf\") String f(){return null;} }");
        writeChain(dir, ".requestMatchers(\"/files/*.pdf\").authenticated().anyRequest().permitAll()");

        ApiModel m = extract(dir);
        assertThat(ep(m, "GET", "/files/report.pdf").security()).isEmpty();
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("SecurityFilterChain"));
    }

    // ======================================================================================
    // @ExceptionHandler: framework-exception status map arms (415 / 405 / 401 / 404 / 413 / 406)
    // ======================================================================================

    @Test
    void frameworkExceptionStatusesFromHandlerParameterTypes(@TempDir Path dir) throws Exception {
        // No @ExceptionHandler(value=...) and no @ResponseStatus -> exception names are derived from the handler's
        // PARAMETER types (handledExceptionNames param branch), then mapped via frameworkExceptionStatus.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler String unsupported(HttpMediaTypeNotSupportedException ex){return null;}\n"
                + "  @ExceptionHandler String badMethod(HttpRequestMethodNotSupportedException ex){return null;}\n"
                + "  @ExceptionHandler String unauth(AuthenticationException ex){return null;}\n"
                + "  @ExceptionHandler String missing(NoHandlerFoundException ex){return null;}\n"
                + "  @ExceptionHandler String tooBig(MaxUploadSizeExceededException ex){return null;}\n"
                + "  @ExceptionHandler String notAcc(NotAcceptableStatusException ex){return null;} }");

        Endpoint e = ep(extract(dir), "GET", "/u");
        assertThat(e.responses()).extracting(ResponseModel::statusCode)
                .contains(415, 405, 401, 404, 413, 406);
    }

    @Test
    void exceptionHandlerValueArrayClassesAreCollectedFirstResolvedStatusWins(@TempDir Path dir) throws Exception {
        // @ExceptionHandler({A.class, B.class}) -> collectClassNames over an ArrayInitializerExpr (L1298-1299).
        // With no @ResponseStatus / body status, handledExceptionStatus resolves the FIRST handled exception that
        // maps (BadCredentialsException -> 401); the array branch is exercised even though only one status emits.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler({BadCredentialsException.class, AccessDeniedException.class})\n"
                + "  String h(){return null;} }");

        Endpoint e = ep(extract(dir), "GET", "/u");
        assertThat(e.responses())
                .anyMatch(r -> r.statusCode() == 401 && "EXCEPTION_HANDLER".equals(r.origin()));
    }

    @Test
    void exceptionHandlerValueMemberOnNormalAnnotationIsRead(@TempDir Path dir) throws Exception {
        // @ExceptionHandler(value = SomeEx.class) — a NormalAnnotationExpr with a value pair (L1280-1282).
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(value = ConstraintViolationException.class) String h(){return null;} }");

        Endpoint e = ep(extract(dir), "GET", "/u");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).contains(400);
    }

    // ======================================================================================
    // @ExceptionHandler: ProblemDetail.forStatus / new ResponseEntity<>(..., HttpStatus.X) error statuses
    // ======================================================================================

    @Test
    void problemDetailForStatusErrorStatusIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class)\n"
                + "  ProblemDetail h(){ return ProblemDetail.forStatus(HttpStatus.NOT_FOUND); } }");

        Endpoint e = ep(extract(dir), "GET", "/u");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).contains(404);
    }

    @Test
    void problemDetailForStatusAndDetailErrorStatusIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class)\n"
                + "  ProblemDetail h(){ return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, \"dup\"); } }");

        Endpoint e = ep(extract(dir), "GET", "/u");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).contains(409);
    }

    @Test
    void problemDetailNon4xxStatusIsIgnored(@TempDir Path dir) throws Exception {
        // forStatus(HttpStatus.OK) -> status < 400 -> NOT added as an error response (problemDetailStatuses filters
        // out the 2xx); the handler then resolves no status and records an honest "could not be resolved
        // statically" blind spot. The endpoint keeps only its OWN 200 (RETURN) — no EXCEPTION_HANDLER 200 leaks.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class)\n"
                + "  ProblemDetail h(){ return ProblemDetail.forStatus(HttpStatus.OK); } }");

        ApiModel m = extract(dir);
        Endpoint e = ep(m, "GET", "/u");
        assertThat(e.responses())
                .noneMatch(r -> r.statusCode() == 200 && "EXCEPTION_HANDLER".equals(r.origin()));
        // the endpoint's own RETURN 200 is the only 200 present
        assertThat(e.responses()).filteredOn(r -> r.statusCode() == 200)
                .allMatch(r -> "RETURN".equals(r.origin()));
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("could not be resolved statically"));
    }

    @Test
    void newResponseEntityIntLiteralStatusIsResolved(@TempDir Path dir) throws Exception {
        // new ResponseEntity<>(body, 422) — the status is the LAST arg, parsed as an int literal (statusFromText).
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class)\n"
                + "  ResponseEntity<String> h(){ return new ResponseEntity<>(\"e\", 422); } }");

        Endpoint e = ep(extract(dir), "GET", "/u");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).contains(422);
    }

    // ======================================================================================
    // Advice media types: MediaType.X / *_VALUE / parseMediaType / valueOf / string literal
    // ======================================================================================

    @Test
    void adviceMediaTypeFromMediaTypeValueConstantIsResolved(@TempDir Path dir) throws Exception {
        // contentType(MediaType.APPLICATION_XML_VALUE) — the *_VALUE suffix is stripped before the constant map.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)\n"
                + "  ResponseEntity<String> h(){ return ResponseEntity.badRequest()"
                + ".contentType(MediaType.APPLICATION_XML_VALUE).body(\"e\"); } }");

        ResponseModel r = resp(ep(extract(dir), "GET", "/u"), 400);
        assertThat(r.mediaTypes()).contains("application/xml");
    }

    @Test
    void adviceMediaTypeFromParseMediaTypeStringIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)\n"
                + "  ResponseEntity<String> h(){ return ResponseEntity.badRequest()"
                + ".contentType(MediaType.parseMediaType(\"text/csv\")).body(\"e\"); } }");

        ResponseModel r = resp(ep(extract(dir), "GET", "/u"), 400);
        assertThat(r.mediaTypes()).contains("text/csv");
    }

    @Test
    void adviceMediaTypeFromValueOfStringIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)\n"
                + "  ResponseEntity<String> h(){ return ResponseEntity.badRequest()"
                + ".contentType(MediaType.valueOf(\"application/hal+json\")).body(\"e\"); } }");

        ResponseModel r = resp(ep(extract(dir), "GET", "/u"), 400);
        assertThat(r.mediaTypes()).contains("application/hal+json");
    }

    @Test
    void adviceMediaTypeFromBareStringLiteralIsResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)\n"
                + "  ResponseEntity<String> h(){ return ResponseEntity.badRequest()"
                + ".contentType(\"application/json\").body(\"e\"); } }");

        ResponseModel r = resp(ep(extract(dir), "GET", "/u"), 400);
        assertThat(r.mediaTypes()).contains("application/json");
    }

    // ======================================================================================
    // ResponseEntity factory statuses: accepted / badRequest / notFound / unprocessableEntity /
    // internalServerError / noContent (in a controller body, via responseEntityStatuses)
    // ======================================================================================

    @Test
    void responseEntityFactoryStatusesAreAllResolved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "import org.springframework.http.ResponseEntity;\n@RestController class Ctrl {\n"
                + "  @PostMapping(\"/a\") ResponseEntity<String> a(int sel){\n"
                + "    if (sel == 0) return ResponseEntity.accepted().build();\n"
                + "    if (sel == 1) return ResponseEntity.badRequest().build();\n"
                + "    if (sel == 2) return ResponseEntity.notFound().build();\n"
                + "    if (sel == 3) return ResponseEntity.unprocessableEntity().build();\n"
                + "    if (sel == 4) return ResponseEntity.internalServerError().build();\n"
                + "    return ResponseEntity.noContent().build(); } }");

        Endpoint e = ep(extract(dir), "POST", "/a");
        assertThat(e.responses()).extracting(ResponseModel::statusCode)
                .contains(202, 400, 404, 422, 500, 204);
    }

    @Test
    void responseEntityStatusWithHttpStatusNameIsResolved(@TempDir Path dir) throws Exception {
        // ResponseEntity.status(HttpStatus.X) routes through statusFromArg -> statusFromText, covering several
        // HttpStatus-name arms (CREATED / FORBIDDEN / SERVICE_UNAVAILABLE) at once.
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "import org.springframework.http.*;\n@RestController class Ctrl {\n"
                + "  @PostMapping(\"/s\") ResponseEntity<String> s(int sel){\n"
                + "    if (sel == 0) return ResponseEntity.status(HttpStatus.CREATED).build();\n"
                + "    if (sel == 1) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();\n"
                + "    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); } }");

        Endpoint e = ep(extract(dir), "POST", "/s");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).contains(201, 403, 503);
    }

    // ======================================================================================
    // statusFromText HttpStatus-name arms via @ExceptionHandler ResponseEntity.status(HttpStatus.X)
    // ======================================================================================

    @Test
    void exceptionHandlerStatusFromVariousHttpStatusNames(@TempDir Path dir) throws Exception {
        // Each .status(HttpStatus.X) error is read by responseEntityStatuses, exercising the UNAUTHORIZED /
        // NOT_ACCEPTABLE / BAD_GATEWAY arms of statusFromText (all >= 400).
        Files.writeString(dir.resolve("Ctrl.java"), HDR
                + "@RestController class Ctrl { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(IllegalStateException.class)\n"
                + "  ResponseEntity<String> a(){ return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); }\n"
                + "  @ExceptionHandler(IllegalArgumentException.class)\n"
                + "  ResponseEntity<String> b(){ return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build(); }\n"
                + "  @ExceptionHandler(RuntimeException.class)\n"
                + "  ResponseEntity<String> c(){ return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build(); } }");

        Endpoint e = ep(extract(dir), "GET", "/u");
        assertThat(e.responses()).extracting(ResponseModel::statusCode).contains(401, 406, 502);
    }

    // ======================================================================================
    // @ResponseStatus OK / ACCEPTED arms of responseStatus()
    // ======================================================================================

    @Test
    void responseStatusOkYields200(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.HttpStatus;\n"
                + "@RestController class C { @ResponseStatus(HttpStatus.OK) @GetMapping(\"/u\") String g(){return null;} }");

        assertThat(ep(extract(dir), "GET", "/u").responses().get(0).statusCode()).isEqualTo(200);
    }

    // ======================================================================================
    // openApiType scalar arms: UUID / boolean / LocalDate / LocalDateTime  (as DTO fields)
    // ======================================================================================

    @Test
    void scalarFieldTypesMapToOpenApiTypesAndFormats(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dto.java"), "package demo;\n"
                + "import java.util.UUID; import java.time.LocalDate; import java.time.LocalDateTime;\n"
                + "public class Dto { public UUID id; public boolean active; public LocalDate day;"
                + " public LocalDateTime ts; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/d\") Dto g(){return null;} }");

        SchemaModel dto = extract(dir).schemas().get("Dto");
        FieldModel id = field(dto, "id");
        assertThat(id.type()).isEqualTo("string");
        assertThat(id.format()).isEqualTo("uuid");
        assertThat(field(dto, "active").type()).isEqualTo("boolean");
        FieldModel day = field(dto, "day");
        assertThat(day.type()).isEqualTo("string");
        assertThat(day.format()).isEqualTo("date");
        FieldModel ts = field(dto, "ts");
        assertThat(ts.type()).isEqualTo("string");
        assertThat(ts.format()).isEqualTo("date-time");
    }

    // ======================================================================================
    // Array-form annotation literal (literal() with {a,b}) — consumes member as a single-element array
    // ======================================================================================

    @Test
    void requestParamValueArrayFormTakesFirstAlias(@TempDir Path dir) throws Exception {
        // @RequestParam(value = {"q", "query"}) — firstString reads the array-form literal, taking the first entry.
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/s\") String g(@RequestParam(value={\"q\",\"query\"}) String q){return null;} }");

        Endpoint e = ep(extract(dir), "GET", "/s");
        assertThat(e.params()).extracting("name").contains("q");
    }

    // ======================================================================================
    // verbsFrom: empty method array on @RequestMapping defaults to GET
    // ======================================================================================

    @Test
    void requestMappingWithEmptyMethodArrayDefaultsToGet(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @RequestMapping(value=\"/x\", method={}) String g(){return null;} }");

        Endpoint e = ep(extract(dir), "GET", "/x");
        assertThat(e.method()).isEqualTo(HttpMethod.GET);
    }

    // ======================================================================================
    // resolvePathExpr: a NameExpr referencing an unknown constant -> null -> blind spot
    // ======================================================================================

    @Test
    void unknownBareConstantPathRefRecordsBlindSpot(@TempDir Path dir) throws Exception {
        // A bare NameExpr (not Owner.NAME) that is not a known constant resolves to null -> "could not be resolved".
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(UNKNOWN_PATH) String g(){return null;} }");

        ApiModel m = extract(dir);
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("could not be resolved to a literal"));
    }

    // ======================================================================================
    // Collection field with a wildcard type-argument (collectionElement single type-arg + wildcard recover)
    // ======================================================================================

    @Test
    void listOfWildcardBoundFieldBecomesArrayOfBound(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Item.java"), "package demo; public class Item { public String sku; }");
        Files.writeString(dir.resolve("Bag.java"), "package demo; import java.util.List;\n"
                + "public class Bag { public List<? extends Item> items; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/b\") Bag g(){return null;} }");

        SchemaModel bag = extract(dir).schemas().get("Bag");
        FieldModel items = field(bag, "items");
        assertThat(items.type()).isEqualTo("array");
        assertThat(items.refSchema()).isEqualTo("Item[]");   // wildcard bound Item recovered as the element DTO
    }

    // ======================================================================================
    // HttpEntity (non-ResponseEntity) generic unwrap -> inner body, responseEntity flag set
    // ======================================================================================

    @Test
    void httpEntityGenericUnwrapsToInnerBody(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("User.java"), "package demo; public class User { public String id; }");
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.HttpEntity;\n@RestController class C {"
                + " @GetMapping(\"/u\") HttpEntity<User> g(){return null;} }");

        ResponseModel r = extract(dir).endpoints().get(0).responses().get(0);
        assertThat(r.schemaRef()).isEqualTo("User");
        assertThat(r.origin()).isEqualTo("RESPONSE_ENTITY");
    }

    @Test
    void rawHttpEntityHasNoSchemaButIsStillABody(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), HDR
                + "import org.springframework.http.HttpEntity;\n@RestController class C {"
                + " @GetMapping(\"/u\") HttpEntity g(){return null;} }");

        ResponseModel r = extract(dir).endpoints().get(0).responses().get(0);
        assertThat(r.schemaRef()).isNull();
        assertThat(r.origin()).isEqualTo("RESPONSE_ENTITY");
        assertThat(r.statusCode()).isEqualTo(200);
    }

    // ======================================================================================
    // @ExceptionHandler producing a 2xx ResponseEntity is NOT leaked as a success body onto endpoints
    // ======================================================================================

    @Test
    void exceptionHandler2xxOutputIsNotLeakedOntoEndpoints(@TempDir Path dir) throws Exception {
        // The handler "produces" 200 via ResponseEntity.ok — but errorStatuses filters out < 400, so no status
        // resolves and an honest blind spot is recorded rather than a phantom 200 error response.
        Files.writeString(dir.resolve("C.java"), HDR
                + "@RestController class C { @GetMapping(\"/u\") String g(){return null;} }");
        Files.writeString(dir.resolve("Advice.java"), HDR
                + "import org.springframework.http.*;\n"
                + "@RestControllerAdvice class Advice {\n"
                + "  @ExceptionHandler(RuntimeException.class)\n"
                + "  ResponseEntity<String> h(){ return ResponseEntity.ok().body(\"recovered\"); } }");

        ApiModel m = extract(dir);
        Endpoint e = ep(m, "GET", "/u");
        // only the endpoint's own 200 (RETURN) is present — no EXCEPTION_HANDLER 200 from the advice
        assertThat(e.responses()).noneMatch(r -> r.statusCode() == 200 && "EXCEPTION_HANDLER".equals(r.origin()));
        assertThat(m.blindSpots()).anyMatch(b -> b.contains("could not be resolved statically"));
    }

    // ======================================================================================
    // Service-hop reachability: a framework exception thrown by a called service maps to its status
    // ======================================================================================

    @Test
    void serviceThrowingFrameworkExceptionIsReachableFromEndpoint(@TempDir Path dir) throws Exception {
        // The service throws a framework exception (no @ResponseStatus, no advice) — resolved via the framework map
        // (exceptionStatusOf -> frameworkExceptionStatus), attached as EXCEPTION_HANDLER_REACHABLE.
        Files.writeString(dir.resolve("Service.java"), "package demo;\n"
                + "import org.springframework.web.server.ResponseStatusException;\n"
                + "class Service { String load(String id){\n"
                + "  if (id == null) throw new AccessDeniedException(\"no\");\n"
                + "  return id; } }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {\n"
                + "  private final Service service = new Service();\n"
                + "  @GetMapping(\"/u/{id}\") String g(@PathVariable String id){ return service.load(id); } }");

        Endpoint e = ep(extract(dir), "GET", "/u/{id}");
        assertThat(e.responses())
                .anyMatch(r -> r.statusCode() == 403 && "EXCEPTION_HANDLER_REACHABLE".equals(r.origin()));
    }

    // ======================================================================================
    // Throws-clause derived exception (declared throws X) resolved via the exception's own @ResponseStatus
    // ======================================================================================

    @Test
    void serviceDeclaredThrowsWithResponseStatusIsReachable(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("GoneEx.java"), HDR
                + "import org.springframework.http.HttpStatus;\n"
                + "@ResponseStatus(HttpStatus.NOT_FOUND) class GoneEx extends Exception {}");
        Files.writeString(dir.resolve("Service.java"), "package demo;\n"
                + "class Service { String load(String id) throws GoneEx { return id; } }");
        Files.writeString(dir.resolve("C.java"), HDR + "@RestController class C {\n"
                + "  private final Service service = new Service();\n"
                + "  @GetMapping(\"/u/{id}\") String g(@PathVariable String id) throws Exception { return service.load(id); } }");

        Endpoint e = ep(extract(dir), "GET", "/u/{id}");
        assertThat(e.responses())
                .anyMatch(r -> r.statusCode() == 404 && "EXCEPTION_HANDLER_REACHABLE".equals(r.origin()));
    }
}