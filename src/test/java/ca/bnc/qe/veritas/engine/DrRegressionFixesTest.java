package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Six regressions an adversarial review caught in the deep-review (DR) fix batch — each is a NEW false positive,
 * silent-drop, or crash that the DR changes introduced and that this batch repairs:
 * <ol>
 *   <li>classPaths' base-class @RequestMapping walk had no cross-call cycle guard → StackOverflow on a cyclic/self
 *       supertype chain → the whole extraction crashed.</li>
 *   <li>looksLikeSecurityAnnotation's "secur" token false-flagged the swagger @SecurityRequirement DOC annotation, and
 *       an unresolved-only token drove a scored CRITICAL SECURITY_MISMATCH.</li>
 *   <li>bindsAllQueryParams' loose contains() let a method whose name is a PREFIX of a sibling's inherit the sibling's
 *       flatten-suppression → a real PARAM_EXTRA was silently dropped.</li>
 *   <li>allEntityStatuses harvested a ResponseEntity built but NEVER returned → a phantom status (false STATUS gap).</li>
 *   <li>the nested object/array-vs-scalar flip double-fired with compareSchema for an array-of-DTO field.</li>
 *   <li>@Positive on an integer field (minimum 0, exclusive) false-diffed against the equivalent spec `minimum: 1`.</li>
 * </ol>
 */
class DrRegressionFixesTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static ApiModel spec(String yaml) {
        return new OpenApiModelExtractor().extract("spec", yaml).model();
    }

    // (1) classPaths cycle guard — mutually-extending controllers with no class-level @RequestMapping must not crash.
    @Test
    void mutuallyExtendingControllersDoNotStackOverflow(@TempDir Path dir) throws Exception {
        write(dir, "Aaa.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController public class Aaa extends Bbb { @GetMapping("/a") public String a() { return "a"; } }
            """);
        write(dir, "Bbb.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController public class Bbb extends Aaa { @GetMapping("/b") public String b() { return "b"; } }
            """);
        assertThatCode(() -> {
            ApiModel m = new JavaSpringExtractor().extract(dir);
            assertThat(m.endpoints()).extracting(e -> e.pathTemplate()).contains("/a", "/b");
        }).doesNotThrowAnyException();
    }

    // (2a) swagger @SecurityRequirement is OpenAPI documentation, not runtime authz — not "secured", no SECURITY_MISMATCH.
    @Test
    void swaggerSecurityRequirementIsNotTreatedAsSecured(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import io.swagger.v3.oas.annotations.security.SecurityRequirement;
            @RestController class C {
                @SecurityRequirement(name = "bearerAuth")
                @GetMapping("/x") public String x() { return "x"; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        assertThat(code.endpoints().get(0).security()).isEmpty();
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /x: { get: { responses: { '200': { description: ok } } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);
    }

    // (2b) An unresolved-ONLY suggestive annotation is a blind spot, not a scored CRITICAL; a RESOLVED token still fires.
    @Test
    void unresolvedOnlySecurityIsBlindSpotButResolvedStillFires(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @AdminApi @GetMapping("/u") public String u() { return "u"; }
            }
            """);
        ApiModel unresolved = new JavaSpringExtractor().extract(dir);
        ApiModel openSpec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /u: { get: { responses: { '200': { description: ok } } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(unresolved, openSpec))
                .noneMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);   // not a CRITICAL — just a blind spot
        assertThat(unresolved.blindSpots().toString()).contains("AdminApi").contains("could not be resolved");

        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import jakarta.annotation.security.DenyAll;
            @RestController class C {
                @DenyAll @GetMapping("/u") public String u() { return "u"; }
            }
            """);
        ApiModel denied = new JavaSpringExtractor().extract(dir);
        assertThat(new DiffEngine().diffCodeVsSpec(denied, openSpec))
                .anyMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH);     // resolved denyAll → real mismatch
    }

    // (3) bindsAllQueryParams marker must be exact: list() must NOT inherit listByStatus()'s pagination suppression.
    @Test
    void prefixNamedSiblingDoesNotInheritFlattenSuppression(@TempDir Path dir) throws Exception {
        write(dir, "OrderController.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.data.domain.Pageable;
            @RestController class OrderController {
                @GetMapping("/orders") public Object list() { return null; }
                @GetMapping("/orders/by-status") public Object listByStatus(Pageable p) { return null; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /orders:
                get:
                  parameters: [ { name: status, in: query, schema: { type: string } } ]
                  responses: { '200': { description: ok } }
              /orders/by-status:
                get:
                  parameters: [ { name: page, in: query, schema: { type: integer } } ]
                  responses: { '200': { description: ok } }
            """);
        var findings = new DiffEngine().diffCodeVsSpec(code, spec);
        // list() binds NOTHING — its missing `status` query param is a real PARAM_EXTRA (was suppressed by the bug).
        assertThat(findings).anyMatch(f -> f.getType() == FindingType.PARAM_EXTRA
                && f.getSummary() != null && f.getSummary().contains("status"));
        // listByStatus() binds a Pageable — its `page` param is still correctly suppressed (the intended behaviour).
        assertThat(findings).noneMatch(f -> f.getType() == FindingType.PARAM_EXTRA
                && f.getSummary() != null && f.getSummary().contains("'page'"));
    }

    // (4) A ResponseEntity built but never returned must not contribute a phantom status.
    @Test
    void strayNonReturnedResponseEntityDoesNotPhantomStatus(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            import java.util.function.Supplier;
            @RestController class C {
                @GetMapping("/x") public ResponseEntity<String> g() {
                    Supplier<ResponseEntity<String>> fallback = () -> new ResponseEntity<>("nope", HttpStatus.NOT_FOUND);
                    return ResponseEntity.ok("x");
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(m.endpoints().get(0).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(404));
    }

    // (4b) Return-scoping must still harvest a status set via an if/else assignment to the returned local (not dropped).
    @Test
    void ifElseAssignedThenReturnedResponseEntityStatusesAreHarvested(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                private boolean flag;
                @GetMapping("/x") public ResponseEntity<String> g() {
                    ResponseEntity<String> r;
                    if (flag) { r = ResponseEntity.ok("x"); }
                    else { r = ResponseEntity.status(HttpStatus.CREATED).body("y"); }
                    return r;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).extracting(r -> r.statusCode()).contains(200, 201);
    }

    // (4c) An ALIASED returned local (built -> r -> return r) can't be followed syntactically without order-blind
    // mis-attribution — so it must surface a BLIND SPOT, never a phantom 200 and never a guessed status.
    @Test
    void aliasedReturnedLocalSurfacesBlindSpotNotPhantom(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                @PostMapping("/a") public ResponseEntity<String> create() {
                    ResponseEntity<String> built = ResponseEntity.status(HttpStatus.CREATED).body("x");
                    ResponseEntity<String> r = built;
                    return r;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }

    // (4d) A returned FIELD set via `this.r = ...` is likewise unresolvable syntactically (a field write can collide
    // with a same-named local) — surface a BLIND SPOT, not a phantom 200.
    @Test
    void thisFieldReturnedSurfacesBlindSpotNotPhantom(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                private ResponseEntity<String> r;
                @PostMapping("/a") public ResponseEntity<String> create() {
                    this.r = ResponseEntity.status(HttpStatus.CREATED).body("x");
                    return r;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }

    // (4e) A `this.<field>` write must NOT be harvested as a status of a same-named shadowing LOCAL that is returned —
    // the local's status wins; the unrelated field write (a cached error) must not become a phantom response.
    @Test
    void thisFieldWriteDoesNotPhantomShadowingLocalReturn(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                private ResponseEntity<String> response;
                @PostMapping("/x") public ResponseEntity<String> create() {
                    this.response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                    ResponseEntity<String> response = ResponseEntity.status(HttpStatus.CREATED).body("x");
                    return response;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        assertThat(m.endpoints().get(0).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(503));
    }

    // (4f) Order-blind aliasing must not fabricate a status: a post-copy reassignment of the alias SOURCE does not
    // change the returned variable, so harvesting it would be a phantom — surface a blind spot instead.
    @Test
    void orderBlindAliasReassignmentDoesNotPhantom(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                @PostMapping("/x") public ResponseEntity<String> create() {
                    ResponseEntity<String> built = ResponseEntity.status(HttpStatus.CREATED).body("x");
                    ResponseEntity<String> r = built;
                    built = ResponseEntity.status(HttpStatus.GONE).build();
                    return r;
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(410));
    }

    // (5b) Array-of-DTO-vs-scalar flip must STILL fire when the enclosing pair differs only by case (compareSchema gate
    // is now case-sensitive, mirroring the components loop — neither path covered the case-skew pair before).
    @Test
    void arrayOfDtoVsScalarFlipFiresForCaseSkewEnclosingPair(@TempDir Path dir) throws Exception {
        write(dir, "Item.java", "public class Item { public String sku; }");
        write(dir, "OrderResponse.java", "import java.util.*; public class OrderResponse { public List<Item> items; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/o") public OrderResponse g() { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        // spec component is the SAME name but lower-cased — the components loop (exact key) never reaches it.
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /o:
                get:
                  responses:
                    '200':
                      description: ok
                      content: { application/json: { schema: { $ref: '#/components/schemas/orderresponse' } } }
            components:
              schemas:
                orderresponse: { type: object, properties: { items: { type: string } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .anyMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH
                        && f.getSummary() != null && f.getSummary().contains("items"));
    }

    // (6b) A fractional field (BigDecimal -> number) must NOT be folded as integer just because the spec carries an
    // int32/int64 format — @Positive (>0) vs spec minimum:1 (>=1) is a REAL divergence on a number (0.5 passes one).
    @Test
    void numberFieldWithIntFormatIsNotFoldedAsInteger(@TempDir Path dir) throws Exception {
        write(dir, "Account.java", """
            import jakarta.validation.constraints.Positive;
            import java.math.BigDecimal;
            public class Account { @Positive public BigDecimal balance; }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/a") public Account create(@RequestBody Account a) { return a; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /a:
                post:
                  requestBody:
                    content: { application/json: { schema: { $ref: '#/components/schemas/Account' } } }
                  responses: { '200': { description: ok } }
            components:
              schemas:
                Account: { type: object, properties: { balance: { type: number, format: int64, minimum: 1 } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .anyMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                        && f.getSummary() != null && f.getSummary().contains("balance"));
    }

    // (6c) A genuine integer CODE field whose spec OMITS `type` (only declares `minimum`) must still fold: the code's
    // value space is the integers, so @Positive (>0) conforms to spec minimum:1 — no false CONSTRAINT_GAP.
    @Test
    void integerCodeFieldVsTypelessSpecMinimumIsNotAFalseGap(@TempDir Path dir) throws Exception {
        write(dir, "Order.java", """
            import jakarta.validation.constraints.Positive;
            public class Order { @Positive public Integer qty; }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/o") public Order create(@RequestBody Order o) { return o; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        // spec property declares minimum but NO type (common in loose / hand-written specs).
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /o:
                post:
                  requestBody:
                    content: { application/json: { schema: { $ref: '#/components/schemas/Order' } } }
                  responses: { '200': { description: ok } }
            components:
              schemas:
                Order: { type: object, properties: { qty: { minimum: 1 } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                        && f.getSummary() != null && f.getSummary().contains("qty"));
    }

    // (5) An array-of-DTO field vs a scalar must be reported ONCE, not double-counted with compareSchema.
    @Test
    void arrayOfDtoVsScalarFieldFlipFiresExactlyOnce(@TempDir Path dir) throws Exception {
        write(dir, "Item.java", "public class Item { public String sku; }");
        write(dir, "OrderResponse.java", "import java.util.*; public class OrderResponse { public List<Item> items; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/o") public OrderResponse g() { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /o:
                get:
                  responses:
                    '200':
                      description: ok
                      content: { application/json: { schema: { $ref: '#/components/schemas/OrderDto' } } }
            components:
              schemas:
                OrderDto: { type: object, properties: { items: { type: string } } }
            """);
        long itemsTypeMismatches = new DiffEngine().diffCodeVsSpec(code, spec).stream()
                .filter(f -> f.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH)
                .filter(f -> f.getSummary() != null && f.getSummary().contains("items"))
                .count();
        assertThat(itemsTypeMismatches).isEqualTo(1L);
    }

    // (6) @Positive on an integer field (>0) is the same constraint as spec `minimum: 1` (>=1) — no false CONSTRAINT_GAP.
    @Test
    void positiveOnIntegerFieldMatchesSpecMinimumOne(@TempDir Path dir) throws Exception {
        write(dir, "Order.java", """
            import jakarta.validation.constraints.Positive;
            public class Order { @Positive public Integer qty; }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/o") public Order create(@RequestBody Order o) { return o; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /o:
                post:
                  requestBody:
                    content: { application/json: { schema: { $ref: '#/components/schemas/Order' } } }
                  responses: { '200': { description: ok } }
            components:
              schemas:
                Order: { type: object, properties: { qty: { type: integer, minimum: 1 } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                        && f.getSummary() != null && f.getSummary().contains("qty"));
    }
}
