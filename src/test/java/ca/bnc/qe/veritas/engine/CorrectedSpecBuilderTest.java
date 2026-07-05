package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import org.junit.jupiter.api.Test;

/** A6: the deterministic corrected OpenAPI must be valid (round-trips) and carry the code's endpoints/schemas. */
class CorrectedSpecBuilderTest {

    @Test
    void buildsValidRoundTrippingSpecFromCode() {
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        ParamModel id = new ParamModel("id", ParamLocation.PATH, "string", null, true, ConstraintSet.empty(), src);
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things/{id}", "getThing", List.of(id), null,
                List.of(new ResponseModel(200, "Thing", null, "RETURN", src)), null, null, List.of(), src);
        SchemaModel thing = new SchemaModel("Thing", "object",
                List.of(new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("Thing", thing));

        String yaml = new CorrectedSpecBuilder().build(code, "Things API");
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", yaml);

        assertThat(reparsed.parsed()).isTrue();   // valid OpenAPI — round-trips cleanly
        assertThat(reparsed.model().endpoints()).anyMatch(e -> e.signature().equals("GET /things/{id}"));
        assertThat(reparsed.model().schemas()).containsKey("Thing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scalarArrayFieldEmitsTypedItems_whileDtoArrayStillEmitsRefItems() throws Exception {
        // Regression: a scalar array (refSchema "string[]") must render items:{type: string} — NOT a bare type:array
        // (self-contradictory vs a String[] field) and NOT items:{$ref:.../string}. A DTO array ("Thing[]") keeps
        // items:{$ref}. Guards the ConstraintReader → CorrectedSpecBuilder element-type path.
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things", "getThings", List.of(), null,
                List.of(new ResponseModel(200, "Thing", null, "RETURN", src)), null, null, List.of(), src);
        SchemaModel thing = new SchemaModel("Thing", "object", List.of(
                new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, src),
                new FieldModel("tags", "array", null, false, ConstraintSet.empty(), "string[]", src),
                new FieldModel("related", "array", null, false, ConstraintSet.empty(), "Thing[]", src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("Thing", thing));

        String yaml = new CorrectedSpecBuilder().build(code, "Things API");
        Map<String, Object> props = (Map<String, Object>) nested(
                new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readValue(yaml, Map.class),
                "components", "schemas", "Thing", "properties");

        Map<String, Object> tags = (Map<String, Object>) props.get("tags");
        assertThat(tags).containsEntry("type", "array");
        Map<String, Object> tagItems = (Map<String, Object>) tags.get("items");
        assertThat(tagItems).containsEntry("type", "string").doesNotContainKey("$ref");

        Map<String, Object> related = (Map<String, Object>) props.get("related");
        Map<String, Object> relItems = (Map<String, Object>) related.get("items");
        assertThat(String.valueOf(relItems.get("$ref"))).contains("Thing");
        assertThat(relItems).doesNotContainKey("type");
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String... path) {
        Object cur = root;
        for (String k : path) {
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur;
    }

    @Test
    void overlayPreservesInfoAndServersWhenOriginalExceedsSnakeyamls3MbLimit() {
        // Regression: snakeyaml's default 3 MB code-point limit made the metadata overlay silently no-op for a large
        // real /v3/api-docs (springdoc emits one minified line), so the corrected drop-in YAML kept placeholder info
        // (version 1.0.0, no servers). With the lifted limit it must parse and carry the real title/version/servers.
        StringBuilder big = new StringBuilder(3_500_000)
                .append("openapi: 3.0.0\n")
                .append("info:\n  title: CIAM Policies\n  version: 1.0.5\n")
                .append("servers:\n  - url: https://api.example.com/ciam\n")
                .append("paths: {}\n");
        // Pad past the 3,145,728 code-point limit with comment lines — counted by the reader, discarded on parse.
        while (big.length() < 3_300_000) {
            big.append("# padding line to exceed the three-megabyte snakeyaml code-point limit for this test\n");
        }
        String corrected = new CorrectedSpecBuilder().build(codeWithGetThings(), "Placeholder");

        String out = new CorrectedSpecBuilder().withOriginalMetadata(corrected, big.toString());

        assertThat(out).contains("CIAM Policies").contains("1.0.5").contains("https://api.example.com/ciam");
    }

    @Test
    void overlayPreservesInfoWhenOriginalHasDuplicateKeys() {
        // snakeyaml rejects duplicate keys by default; swagger-parser accepts them (last-wins). A hand-rolled
        // /v3/api-docs with a repeated key must not blank the overlay back to placeholders.
        String original = "openapi: 3.0.0\n"
                + "info:\n  title: Dup API\n  version: 2.0.0\n  version: 2.0.0\n"
                + "servers:\n  - url: https://dup.example.com\n"
                + "paths: {}\n";
        String corrected = new CorrectedSpecBuilder().build(codeWithGetThings(), "Placeholder");

        String out = new CorrectedSpecBuilder().withOriginalMetadata(corrected, original);

        assertThat(out).contains("Dup API").contains("https://dup.example.com");
    }

    @Test
    void overlaySynthesizesServersFromSwagger2HostBasePathSchemes() {
        // Swagger 2.0 has no `servers` — it uses host/basePath/schemes. The overlay must synthesize a servers list so
        // the corrected drop-in YAML still carries the real server instead of dropping it.
        String original = """
            swagger: '2.0'
            info:
              title: Legacy API
              version: 3.1.0
            host: legacy.example.com
            basePath: /v2
            schemes:
              - https
              - http
            paths: {}
            """;
        String corrected = new CorrectedSpecBuilder().build(codeWithGetThings(), "Placeholder");

        String out = new CorrectedSpecBuilder().withOriginalMetadata(corrected, original);

        assertThat(out).contains("https://legacy.example.com/v2").contains("http://legacy.example.com/v2");
    }

    @Test
    void carriesMetadataDetectsInfoServersHost_andRejectsFragmentsAndJunk() {
        CorrectedSpecBuilder b = new CorrectedSpecBuilder();
        assertThat(b.carriesMetadata("openapi: 3.0.0\ninfo:\n  title: X\n  version: 1.0.0\n"
                + "servers:\n  - url: https://x\npaths: {}\n")).isTrue();          // info + servers
        assertThat(b.carriesMetadata("openapi: 3.0.0\ninfo:\n  title: X\n  version: 1.0.0\npaths: {}\n")).isTrue(); // info only
        assertThat(b.carriesMetadata("swagger: '2.0'\nhost: api.example.com\npaths: {}\n")).isTrue();               // Swagger-2 host
        assertThat(b.carriesMetadata("paths:\n  /x:\n    get:\n      responses: {}\n")).isFalse();  // fragment: no info/servers
        assertThat(b.carriesMetadata("")).isFalse();
        assertThat(b.carriesMetadata(null)).isFalse();
        assertThat(b.carriesMetadata("just a plain scalar, not a mapping")).isFalse();   // unparseable-as-map → false
    }

    /** A code model with one endpoint whose 200 response the original spec also documents (with an example). */
    private static ApiModel codeWithGetThings() {
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        ParamModel id = new ParamModel("id", ParamLocation.PATH, "string", null, true, ConstraintSet.empty(), src);
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things/{id}", "getThing", List.of(id), null,
                List.of(new ResponseModel(200, "Thing", null, "RETURN", src)), null, null, List.of(), src);
        SchemaModel thing = new SchemaModel("Thing", "object",
                List.of(new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, src)), null, src);
        return new ApiModel("code", null, null, null, List.of(ep), Map.of("Thing", thing));
    }

    @Test
    void preservesOriginalInfoServersAndResponseExamples() {
        ApiModel code = codeWithGetThings();
        String original = """
                openapi: 3.0.3
                info:
                  title: CIAM Policies
                  version: 1.0.5
                  description: The real policies API
                servers:
                  - url: https://api.example.com/v1
                  - url: https://sandbox.example.com/v1
                paths:
                  /things/{id}:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              example:
                                name: sample-thing
                """;

        String yaml = new CorrectedSpecBuilder().build(code, "Things API", original);
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", yaml);

        // still valid + code-derived paths/schemas kept (the extractor prefixes the preserved server base path on
        // reparse, so the signature ends with — rather than exactly equals — the code path template)
        assertThat(reparsed.parsed()).isTrue();
        assertThat(reparsed.model().endpoints()).anyMatch(e -> e.signature().endsWith("/things/{id}"));
        assertThat(reparsed.model().schemas()).containsKey("Thing");
        // real info replaces the hard-coded placeholders
        assertThat(yaml).contains("CIAM Policies");
        assertThat(yaml).contains("1.0.5");
        assertThat(yaml).doesNotContain("1.0.0");
        assertThat(yaml).doesNotContain("Things API");   // passed title is overridden by the original's real title
        // original servers copied verbatim
        assertThat(yaml).contains("https://api.example.com/v1");
        assertThat(yaml).contains("https://sandbox.example.com/v1");
        // the response example is lifted into the corrected 200 response
        assertThat(yaml).contains("sample-thing");
    }

    @Test
    void absentOrBlankOriginal_keepsPlaceholders() {
        ApiModel code = codeWithGetThings();

        String noOriginal = new CorrectedSpecBuilder().build(code, "Things API", null);
        String blankOriginal = new CorrectedSpecBuilder().build(code, "Things API", "   ");
        String twoArg = new CorrectedSpecBuilder().build(code, "Things API");

        for (String yaml : List.of(noOriginal, blankOriginal, twoArg)) {
            assertThat(yaml).contains("Things API");   // placeholder title kept
            assertThat(yaml).contains("1.0.0");        // placeholder version kept
            assertThat(yaml).doesNotContain("servers");   // no servers invented
        }
    }

    @Test
    void xExtensionOverlayStillApplied_alongsideMetadataPreservation() {
        ApiModel code = codeWithGetThings();
        String original = """
                openapi: 3.0.3
                info:
                  title: CIAM Policies
                  version: 1.0.5
                x-amazon-apigateway-policy: allow-all
                paths:
                  /things/{id}:
                    x-internal-id: T-42
                    get:
                      x-google-backend: things-backend
                      responses:
                        '200':
                          description: OK
                """;

        String yaml = new CorrectedSpecBuilder().build(code, "Things API", original);
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", yaml);

        assertThat(reparsed.parsed()).isTrue();
        // x-* overlay preserved at root, path-item and operation level
        assertThat(yaml).contains("x-amazon-apigateway-policy");
        assertThat(yaml).contains("x-internal-id");
        assertThat(yaml).contains("x-google-backend");
        // metadata preservation also ran
        assertThat(yaml).contains("CIAM Policies");
        assertThat(yaml).contains("1.0.5");
    }

    @Test
    void codePathNotInOriginal_stillEmittedWithNoExampleAndNoCrash() {
        ApiModel code = codeWithGetThings();
        // original documents a DIFFERENT path — the code's /things/{id} is absent from it
        String original = """
                openapi: 3.0.3
                info:
                  title: CIAM Policies
                  version: 1.0.5
                paths:
                  /other:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              example:
                                other: value
                """;

        String yaml = new CorrectedSpecBuilder().build(code, "Things API", original);
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", yaml);

        assertThat(reparsed.parsed()).isTrue();   // no crash on the unmatched path
        // the code path is still emitted (code is the source of truth for paths)
        assertThat(reparsed.model().endpoints()).anyMatch(e -> e.signature().equals("GET /things/{id}"));
        // the other spec's example did NOT bleed into the corrected code path
        assertThat(yaml).doesNotContain("other: value");
        // real info still preserved
        assertThat(yaml).contains("CIAM Policies");
    }

    // ---- withOriginalMetadata: the same preservation applied to an ALREADY-BUILT yaml (the LLM reconcile branch) ----

    @Test
    void withOriginalMetadata_overlaysRealInfoAndServersOntoAnLlmYaml() {
        // Mirrors the live symptom: the LLM produced placeholder identity ("CIAM Password Policy API" / 1.0.0) and no
        // servers, because it is never handed the spec's info/servers. The overlay must replace them with the real ones.
        String llmYaml = """
                openapi: 3.0.3
                info:
                  title: CIAM Password Policy API
                  version: 1.0.0
                paths:
                  /things/{id}:
                    get:
                      responses:
                        '200':
                          description: OK
                """;
        String original = """
                openapi: 3.0.3
                info:
                  title: CIAM Policies
                  version: 1.0.5
                  description: The real policies API
                servers:
                  - url: https://api.example.com/v1
                  - url: https://sandbox.example.com/v1
                paths:
                  /things/{id}:
                    get:
                      responses:
                        '200':
                          description: OK
                """;

        String out = new CorrectedSpecBuilder().withOriginalMetadata(llmYaml, original);
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", out);

        assertThat(reparsed.parsed()).isTrue();
        // real identity replaces the LLM's invented placeholders
        assertThat(out).contains("CIAM Policies").contains("1.0.5");
        assertThat(out).doesNotContain("CIAM Password Policy API");
        assertThat(out).doesNotContain("version: 1.0.0");
        // the original servers now present (they were absent from the LLM yaml)
        assertThat(out).contains("https://api.example.com/v1").contains("https://sandbox.example.com/v1");
        // the LLM's paths survive — the overlay only touches info/servers/examples/x-*
        assertThat(reparsed.model().endpoints()).anyMatch(e -> e.signature().endsWith("/things/{id}"));
    }

    @Test
    void withOriginalMetadata_passthroughWhenOriginalOrCorrectedMissing() {
        CorrectedSpecBuilder b = new CorrectedSpecBuilder();
        String llmYaml = "openapi: 3.0.3\ninfo:\n  title: X\n  version: 1.0.0\n";
        assertThat(b.withOriginalMetadata(llmYaml, null)).isEqualTo(llmYaml);      // no original → unchanged
        assertThat(b.withOriginalMetadata(llmYaml, "   ")).isEqualTo(llmYaml);     // blank original → unchanged
        assertThat(b.withOriginalMetadata(null, "openapi: 3.0.3")).isNull();       // no corrected → null (as given)
        assertThat(b.withOriginalMetadata("  ", "openapi: 3.0.3")).isEqualTo("  "); // blank corrected → unchanged
    }

    @Test
    void withOriginalMetadata_returnsInputWhenCorrectedYamlIsNotAMapping() {
        // A non-mapping corrected doc can't be enriched — return it untouched, never null (fail-safe).
        String notAMap = "just a scalar string";
        String out = new CorrectedSpecBuilder().withOriginalMetadata(notAMap,
                "openapi: 3.0.3\ninfo:\n  title: CIAM Policies\n  version: 1.0.5\n");
        assertThat(out).isEqualTo(notAMap);
    }
}
