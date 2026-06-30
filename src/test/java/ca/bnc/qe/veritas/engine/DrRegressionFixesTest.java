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
