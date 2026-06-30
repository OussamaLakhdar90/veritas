package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.finding.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end exercise of the Core-1..5 deep-review fixes through the REAL pipeline (JavaSpringExtractor +
 * OpenApiModelExtractor + DiffEngine) on a single realistic controller + DTO + paired OpenAPI spec that combines
 * EVERY changed idiom at once — the integration interactions the isolated unit fixtures don't cover. The two real
 * GitHub projects (spring-petclinic-rest, spring-boot-realworld-example-app) used for dogfood happen to use only
 * plain int/String params and no Bean-Validation on controllers, so they don't hit @Min(0L) / collection params /
 * optional-security; this fixture does.
 */
class CoreFixesPipelineIntegrationTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private ApiModel extractCode(Path dir) {
        return new JavaSpringExtractor().extract(dir);
    }

    /** A realistic controller: long-literal Bean-Validation (the old scan-killer), a collection query param, a Map
     *  bind-all, a nested-brace regex path var, and a @ResponseStatus that isn't one of the 4 the old ladder knew. */
    private void writeRealisticService(Path dir) throws Exception {
        write(dir, "Money.java", """
            import jakarta.validation.constraints.*;
            public class Money {
                @Min(0L) @Max(1_000_000L) public Long cents;     // long literal + underscore — old code threw here
                @Size(min = 3, max = 3) public String currency;
                @Size(min = 1, max = 50) public java.util.List<String> tags;   // @Size on a collection
            }
            """);
        write(dir, "PaymentController.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            import java.util.List;
            import java.util.Map;
            @RestController
            @RequestMapping("/payments")
            public class PaymentController {
                @GetMapping
                public List<Money> list(@RequestParam List<String> statuses,
                                        @RequestParam Map<String, String> filters) { return null; }

                @ResponseStatus(HttpStatus.CONFLICT)
                @PostMapping
                public Money create(@RequestBody Money body) { return null; }

                @GetMapping("/{id:[0-9]{2}}")
                public Money get(@PathVariable String id) { return null; }
            }
            """);
    }

    @Test
    void realisticServiceExtractsWithoutCrashingAndTypesEverythingFaithfully(@TempDir Path dir) throws Exception {
        writeRealisticService(dir);

        ApiModel code = extractCode(dir);   // the WHOLE point: this must not throw on @Min(0L)/@Size(min=1_000)

        var money = code.schemas().get("Money");
        assertThat(money).isNotNull();
        assertThat(money.fields()).anySatisfy(f -> {
            if (f.jsonName().equals("cents")) {
                assertThat(f.constraints().minimum()).isEqualTo(0.0);          // @Min(0L) parsed, not crashed
                assertThat(f.constraints().maximum()).isEqualTo(1_000_000.0);  // @Max(1_000_000L)
            }
        });
        // @Size on a collection field is item-count, not string length → dropped, not a bogus minLength.
        var tags = money.fields().stream().filter(f -> f.jsonName().equals("tags")).findFirst().orElseThrow();
        assertThat(tags.type()).isEqualTo("array");
        assertThat(tags.constraints().minLength()).isNull();

        // GET /payments: collection param "statuses" → array; the Map bind-all → blind spot, NOT a phantom param.
        var listEp = code.endpoints().stream()
                .filter(e -> e.method().name().equals("GET") && e.pathTemplate().equals("/payments"))
                .findFirst().orElseThrow();
        ParamModel statuses = listEp.params().stream()
                .filter(p -> p.name().equals("statuses")).findFirst().orElseThrow();
        assertThat(statuses.type()).isEqualTo("array");
        assertThat(listEp.params()).noneSatisfy(p -> assertThat(p.name()).isEqualTo("filters"));
        assertThat(code.blindSpots()).anySatisfy(b -> assertThat(b).contains("binds all query params"));

        // @ResponseStatus(CONFLICT) → 409 (not a phantom 200); nested-brace path var → clean /payments/{id}.
        var createEp = code.endpoints().stream()
                .filter(e -> e.method().name().equals("POST")).findFirst().orElseThrow();
        assertThat(createEp.responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(409));
        assertThat(code.endpoints()).anySatisfy(e -> assertThat(e.pathTemplate()).isEqualTo("/payments/{id}"));
    }

    @Test
    void fullDiffAgainstAPairedSpecProducesOnlyTrueFindings(@TempDir Path dir) throws Exception {
        writeRealisticService(dir);
        ApiModel code = extractCode(dir);

        // A spec that MATCHES the code on verbs/paths, declares OPTIONAL security (empty {} alternative) on GET, and
        // an array-of-$ref property — the exact spec-side shapes Core-5 fixed. A faithful spec must yield NO
        // verb/endpoint false positives and NO false SECURITY_MISMATCH against the (unsecured) code.
        String spec = """
            openapi: 3.0.1
            info: { title: Payments, version: 1 }
            paths:
              /payments:
                get:
                  security:
                    - ApiKey: []
                    - {}
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema: { type: array, items: { $ref: '#/components/schemas/Money' } }
                post:
                  responses: { '409': { description: conflict } }
              /payments/{id}:
                get:
                  parameters:
                    - { name: id, in: path, required: true, schema: { type: string } }
                  responses: { '200': { description: ok } }
            components:
              securitySchemes:
                ApiKey: { type: apiKey, name: X-Key, in: header }
              schemas:
                Bag:
                  type: object
                  properties:
                    items:
                      type: array
                      items: { $ref: '#/components/schemas/Money' }
                Money:
                  type: object
                  properties:
                    cents: { type: integer, format: int64 }
                    currency: { type: string }
                    tags: { type: array, items: { type: string } }
            """;
        ApiModel specModel = new OpenApiModelExtractor().extract("spec", spec).model();

        List<Finding> findings = new DiffEngine().diffCodeVsSpec(code, specModel);

        // No VERB_MISMATCH and no MISSING/EXTRA endpoint: code and spec agree on GET/POST /payments + GET /payments/{id}.
        assertThat(findings).noneSatisfy(f ->
                assertThat(f.getType().name()).isIn("VERB_MISMATCH", "MISSING_ENDPOINT", "EXTRA_ENDPOINT"));
        // No false SECURITY_MISMATCH — the spec's GET is OPTIONALLY secured ({} alternative), code is unsecured.
        assertThat(findings).noneSatisfy(f -> assertThat(f.getType().name()).isEqualTo("SECURITY_MISMATCH"));
        // The array-of-$ref property kept its element schema (Core-5) — Bag.items resolved to Money[], not null.
        var bag = specModel.schemas().get("Bag");
        var items = bag.fields().stream().filter(f -> f.jsonName().equals("items")).findFirst().orElseThrow();
        assertThat(items.refSchema()).isEqualTo("Money[]");
        // And the optional-security blind spot is surfaced honestly.
        assertThat(specModel.blindSpots()).anySatisfy(b -> assertThat(b).contains("OPTIONAL security"));
    }

    @Test
    void theWholePipelineNeverThrowsOnTheRealisticInput(@TempDir Path dir) throws Exception {
        writeRealisticService(dir);
        assertThatCode(() -> {
            ApiModel code = extractCode(dir);
            String minimalSpec = "openapi: 3.0.1\ninfo: { title: P, version: 1 }\npaths: {}\n";
            ApiModel specModel = new OpenApiModelExtractor().extract("spec", minimalSpec).model();
            new DiffEngine().diffCodeVsSpec(code, specModel);
        }).doesNotThrowAnyException();
    }
}
