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

    // ---- component-name preservation: a drop-in must keep the spec's $ref keys, not rename them to Java DTO names ----

    @Test
    void preservesOriginalComponentNames_insteadOfRenamingThemToJavaDtoClassNames() {
        // The corrected schema is code-derived and keyed by the Java DTO class name (PascalCase `PasswordComplexity`),
        // but the original spec published it as `policies-password-complexity`. Renaming a published $ref key leaks
        // Java naming into the public contract and breaks consumer codegen — the drop-in must keep the spec's name
        // (both the component KEY and every $ref to it). Matched structurally by the shared property-name set.
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/policies/password", "getPasswordPolicy", List.of(), null,
                List.of(new ResponseModel(200, "PasswordComplexity", null, "RETURN", src)), null, null, List.of(), src);
        SchemaModel schema = new SchemaModel("PasswordComplexity", "object", List.of(
                new FieldModel("minLength", "integer", null, true, ConstraintSet.empty(), null, src),
                new FieldModel("requireUppercase", "boolean", null, false, ConstraintSet.empty(), null, src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("PasswordComplexity", schema));
        String original = """
                openapi: 3.0.3
                info:
                  title: CIAM Policies
                  version: 1.0.5
                paths: {}
                components:
                  schemas:
                    policies-password-complexity:
                      type: object
                      properties:
                        minLength: { type: integer }
                        requireUppercase: { type: boolean }
                """;

        String yaml = new CorrectedSpecBuilder().build(code, "Placeholder", original);
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", yaml);

        assertThat(reparsed.parsed()).isTrue();                       // uniform key+$ref rename still round-trips
        assertThat(yaml).contains("policies-password-complexity");    // spec's component name preserved as the key
        assertThat(yaml).doesNotContain("PasswordComplexity");        // Java DTO name gone (component key + response $ref)
    }

    @Test
    void doesNotRenameWhenNoSpecComponentStructurallyMatches() {
        // Safety rail: rename ONLY on a confident structural match. When the spec's components share no property set
        // with the code schema, the code name must stand — never adopted from an unrelated spec component.
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/policies/password", "getPasswordPolicy", List.of(), null,
                List.of(new ResponseModel(200, "PasswordComplexity", null, "RETURN", src)), null, null, List.of(), src);
        SchemaModel schema = new SchemaModel("PasswordComplexity", "object", List.of(
                new FieldModel("minLength", "integer", null, true, ConstraintSet.empty(), null, src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("PasswordComplexity", schema));
        String original = """
                openapi: 3.0.3
                info:
                  title: CIAM Policies
                  version: 1.0.5
                paths: {}
                components:
                  schemas:
                    error-response:
                      type: object
                      properties:
                        code: { type: integer }
                        message: { type: string }
                """;

        String yaml = new CorrectedSpecBuilder().build(code, "Placeholder", original);

        assertThat(yaml).contains("PasswordComplexity");   // unrelated spec component → the code name stands
        assertThat(yaml).doesNotContain("error-response");  // never adopts a structurally-different spec name
    }

    @Test
    void withOriginalMetadata_restoresSpecComponentNamesOnAnLlmYaml() {
        // The LLM reconcile branch: its YAML also named the schema after the Java class. The overlay must rename it
        // (component key + $ref) back to the spec's published component name.
        String llmYaml = """
                openapi: 3.0.3
                info:
                  title: CIAM Password Policy API
                  version: 1.0.0
                paths:
                  /policies/password:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/PasswordComplexity'
                components:
                  schemas:
                    PasswordComplexity:
                      type: object
                      properties:
                        minLength: { type: integer }
                        requireUppercase: { type: boolean }
                """;
        String original = """
                openapi: 3.0.3
                info:
                  title: CIAM Policies
                  version: 1.0.5
                paths: {}
                components:
                  schemas:
                    policies-password-complexity:
                      type: object
                      properties:
                        minLength: { type: integer }
                        requireUppercase: { type: boolean }
                """;

        String out = new CorrectedSpecBuilder().withOriginalMetadata(llmYaml, original);
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", out);

        assertThat(reparsed.parsed()).isTrue();                       // key + $ref renamed consistently → round-trips
        assertThat(out).contains("policies-password-complexity");
        assertThat(out).doesNotContain("PasswordComplexity");
    }

    // ---- structural validity: never emit a $ref to a schema we didn't define, honour the code's media types --------

    @Test
    void errorResponseWithGenericObjectBodyDoesNotEmitADanglingRef() throws Exception {
        // A generic error body typed "Object" (no DTO) must NOT become $ref:#/components/schemas/Object — that's a
        // DANGLING ref (the schema is never defined), which makes the "drop-in" spec invalid OpenAPI (validators +
        // codegen fail). It must degrade to an inline {type: object}. RED before the fix — guards the 8-dangling-refs
        // regression the reviewer caught.
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things/{id}", "getThing", List.of(), null, List.of(
                new ResponseModel(200, "Thing", null, "RETURN", src),
                new ResponseModel(404, "Object", null, "EXCEPTION_HANDLER", src),
                new ResponseModel(500, "Object", null, "EXCEPTION_HANDLER", src)), null, null, List.of(), src);
        SchemaModel thing = new SchemaModel("Thing", "object",
                List.of(new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("Thing", thing));

        CorrectedSpecBuilder b = new CorrectedSpecBuilder();
        String yaml = b.withoutDanglingRefs(b.build(code, "Things API"));   // sanitised at the write boundary
        SpecParse reparsed = new OpenApiModelExtractor().extract("corrected", yaml);

        assertThat(reparsed.parsed()).isTrue();
        assertThat(yaml).doesNotContain("#/components/schemas/Object");   // the dangling ref is gone
        assertThat(everyRefResolves(yaml)).isTrue();                      // NO $ref points at an undefined component
        assertThat(reparsed.model().schemas()).containsKey("Thing");      // the real DTO ref still resolves
    }

    @Test
    void undefinedFieldRefSchemaDegradesToInlineObjectRatherThanDangling() throws Exception {
        // Same guard one level down: a schema field whose ref type isn't a defined DTO must not dangle either.
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things", "getThings", List.of(), null,
                List.of(new ResponseModel(200, "Thing", null, "RETURN", src)), null, null, List.of(), src);
        SchemaModel thing = new SchemaModel("Thing", "object", List.of(
                new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, src),
                new FieldModel("mystery", "object", null, false, ConstraintSet.empty(), "GhostDto", src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("Thing", thing));

        CorrectedSpecBuilder b = new CorrectedSpecBuilder();
        String yaml = b.withoutDanglingRefs(b.build(code, "Things API"));

        assertThat(new OpenApiModelExtractor().extract("corrected", yaml).parsed()).isTrue();
        assertThat(yaml).doesNotContain("GhostDto");
        assertThat(everyRefResolves(yaml)).isTrue();
    }

    @Test
    void responseUsesTheCodeDeclaredMediaTypeNotHardcodedJson() {
        // The corrected spec must emit the media type the code declares (application/problem+json for RFC-7807 error
        // bodies), not a hardcoded application/json that contradicts the report's own media-type findings. RED before.
        SourceRef src = SourceRef.code("X.java", 1, 1, "x");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/things/{id}", "getThing", List.of(), null, List.of(
                new ResponseModel(200, "Thing", List.of("application/json"), "RETURN", src),
                new ResponseModel(404, "Thing", List.of("application/problem+json"), "EXCEPTION_HANDLER", src)),
                null, null, List.of(), src);
        SchemaModel thing = new SchemaModel("Thing", "object",
                List.of(new FieldModel("name", "string", null, true, ConstraintSet.empty(), null, src)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep), Map.of("Thing", thing));

        String yaml = new CorrectedSpecBuilder().build(code, "Things API");

        assertThat(yaml).contains("application/problem+json");
    }

    @Test
    void withoutDanglingRefs_inlinesAnUndefinedRefButKeepsDefinedOnes() {
        // The write-boundary sanitiser (also applied to the LLM branch): an LLM-invented $ref with no matching
        // component (e.g. error-model referenced-but-never-defined) becomes {type: object}; a $ref to a DEFINED
        // component is kept. Mirrors the reviewer's exact regression — 8 dangling error refs + a dropped error-model.
        String llmYaml = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.0 }
                paths:
                  /x:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Thing' }
                        '500':
                          description: Internal Server Error
                          content:
                            application/problem+json:
                              schema: { $ref: '#/components/schemas/error-model' }
                components:
                  schemas:
                    Thing: { type: object, properties: { name: { type: string } } }
                """;
        String out = new CorrectedSpecBuilder().withoutDanglingRefs(llmYaml);

        assertThat(out).doesNotContain("#/components/schemas/error-model");   // undefined → inlined {type: object}
        assertThat(out).contains("#/components/schemas/Thing");                // defined → kept
        assertThat(new OpenApiModelExtractor().extract("corrected", out).parsed()).isTrue();
    }

    // ---- optional enrichment: adopt the original spec's structured error contract for error responses -------------

    @Test
    void withErrorSchemasFromSpec_referencesTheDocumentedErrorSchemaKeepingTheCorrectedMediaType() throws Exception {
        // The code models error bodies as an untyped Object (→ inline {type: object}). When the original spec documents
        // a structured error schema (error-model), reference it — richer — but KEEP the corrected response's own media
        // type (code wins on media type; never swap it to the spec's). Success responses stay code-authoritative.
        String corrected = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.0 }
                paths:
                  /things/{id}:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Thing' }
                        '404':
                          description: Not Found
                          content:
                            application/json:
                              schema: { type: object }
                components:
                  schemas:
                    Thing: { type: object, properties: { name: { type: string } } }
                """;
        String original = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.5 }
                paths:
                  /things/{id}:
                    get:
                      responses:
                        '404':
                          description: Not Found
                          content:
                            application/problem+json:
                              schema: { $ref: '#/components/schemas/error-model' }
                components:
                  schemas:
                    error-model:
                      type: object
                      properties:
                        code: { type: string }
                        message: { type: string }
                """;
        String out = new CorrectedSpecBuilder().withErrorSchemasFromSpec(corrected, original);

        assertThat(out).contains("#/components/schemas/error-model");   // the 404 now references the structured error…
        assertThat(out).contains("error-model:");                       // …and the schema itself was copied into components
        assertThat(out).contains("#/components/schemas/Thing");         // the 200 (success) response is untouched
        assertThat(out).doesNotContain("application/problem+json");     // the spec's media type was NOT adopted (code wins)
        assertThat(new OpenApiModelExtractor().extract("corrected", out).parsed()).isTrue();
        assertThat(everyRefResolves(out)).isTrue();                     // no dangling ref introduced
    }

    @Test
    void withErrorSchemasFromSpec_referencesTheErrorSchemaEvenWhenCodeAndSpecPathsDiffer() throws Exception {
        // The regression the ciam-policies report kept flagging: the CODE's routes carry a context-path prefix and a
        // renamed path var (/ciam/policies vs /policies, {app} vs {appId}), and the code returns application/problem+json
        // generic error bodies for MORE statuses (400/404/406/500) than the spec documents (400/404, via a shared
        // components/responses with an {error: $ref error-model} wrapper and examples). An exact path match can't fire,
        // so nothing was enriched (error-model dropped). Path-independent enrichment must: reference error-model on ALL
        // generic error bodies, KEEP problem+json, copy error-model into components, and NOT drag in the spec's
        // application/json media type or its dangling example refs. RED before the fix (out == corrected, no error-model).
        String corrected = """
                openapi: 3.0.3
                info: { title: CIAM Policies, version: 1.0.0 }
                paths:
                  /ciam/policies:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/policies' }
                        '400': { description: Bad Request, content: { application/problem+json: { schema: { type: object } } } }
                        '404': { description: Not Found, content: { application/problem+json: { schema: { type: object } } } }
                        '406': { description: Response 406, content: { application/problem+json: { schema: { type: object } } } }
                        '500': { description: Internal Server Error, content: { application/problem+json: { schema: { type: object } } } }
                  /ciam/policies/{app}:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/policies' }
                        '404': { description: Not Found, content: { application/problem+json: { schema: { type: object } } } }
                components:
                  schemas:
                    policies: { type: object, properties: { password: { type: string } } }
                """;
        String original = """
                openapi: 3.0.0
                info: { title: CIAM Policies, version: 1.0.5 }
                paths:
                  /policies:
                    get:
                      responses:
                        '200': { $ref: '#/components/responses/200' }
                        '400': { $ref: '#/components/responses/400' }
                        '404': { $ref: '#/components/responses/404' }
                  /policies/{appId}:
                    get:
                      responses:
                        '200': { $ref: '#/components/responses/200' }
                        '400': { $ref: '#/components/responses/400' }
                        '404': { $ref: '#/components/responses/404' }
                components:
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/policies' }
                    '400':
                      description: Bad request
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              error: { $ref: '#/components/schemas/error-model' }
                          examples:
                            policy-bad-request: { $ref: '#/components/examples/400' }
                    '404':
                      description: Not found
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              error: { $ref: '#/components/schemas/error-model' }
                          examples:
                            policy-not-found: { $ref: '#/components/examples/404' }
                  examples:
                    '400': { value: { error: { title: Bad Request } } }
                    '404': { value: { error: { title: Not Found } } }
                  schemas:
                    policies: { type: object, properties: { password: { type: string } } }
                    error-model:
                      type: object
                      required: [type, title, status, message]
                      properties:
                        type: { type: string }
                        title: { type: string }
                        status: { type: integer }
                        message: { type: string }
                """;
        String out = new CorrectedSpecBuilder().withErrorSchemasFromSpec(corrected, original);

        assertThat(out).contains("#/components/schemas/error-model");   // generic error bodies now reference error-model…
        assertThat(out).contains("error-model:");                       // …and the schema was carried into components
        assertThat(out).contains("application/problem+json");           // the CODE's media type is preserved (no drift)
        assertThat(out).doesNotContain("#/components/examples/400");     // the spec's examples were NOT dragged in (no dangling ref)
        // The WHOLE schema shape is preserved: the corrected 400 body is the {error: $ref error-model} WRAPPER the spec
        // documented (matching the code's Collections.singletonMap("error", body) → {"error": {...}}), NOT a flattened
        // top-level $ref that would describe {type,title,…} where the code actually emits {error:{type,title,…}}.
        Map<String, Object> schema400 = schemaNodeAt(out, "/ciam/policies", "get", "400");
        assertThat(schema400).containsEntry("type", "object").doesNotContainKey("$ref");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema400.get("properties");
        assertThat(props).containsKey("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> errorProp = (Map<String, Object>) props.get("error");
        assertThat(errorProp).containsEntry("$ref", "#/components/schemas/error-model");
        assertThat(new OpenApiModelExtractor().extract("corrected", out).parsed()).isTrue();
        assertThat(everyRefResolves(out)).isTrue();                     // every $ref resolves — valid drop-in spec
    }

    @Test
    void withErrorSchemasFromSpec_returnsInputVerbatimWhenTheOriginalHasNoStructuredErrorSchema() {
        // Safety: only adopt when the original error response references a real schema. Otherwise return the input
        // UNCHANGED (so the guarded caller never risks a regression on a spec with nothing to enrich).
        String corrected = "openapi: 3.0.3\ninfo: { title: X, version: 1.0.0 }\n"
                + "paths:\n  /x:\n    get:\n      responses:\n        '500': { description: err }\n";
        String original = "openapi: 3.0.3\ninfo: { title: X, version: 1.0.0 }\n"
                + "paths:\n  /x:\n    get:\n      responses:\n        '500': { description: err }\n"
                + "components:\n  schemas:\n    Unrelated: { type: object }\n";
        assertThat(new CorrectedSpecBuilder().withErrorSchemasFromSpec(corrected, original)).isEqualTo(corrected);
    }

    @Test
    void withErrorSchemasFromSpec_doesNotOverwriteACodeAuthoritativeStructuredErrorBody() {
        // Code wins on behaviour: when the CODE already documents a STRUCTURED error body (its own $ref, e.g. from
        // @ControllerAdvice/@ExceptionHandler returning a real error DTO), the enrichment must NOT replace it with the
        // spec's — that would leak an error shape/media type the code doesn't produce AND resurrect the media-type
        // drift the deterministic build removed. Only the generic {type: object} placeholder is enriched. RED before.
        String corrected = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.0 }
                paths:
                  /things/{id}:
                    get:
                      responses:
                        '404':
                          description: Not Found
                          content:
                            application/problem+json:
                              schema: { $ref: '#/components/schemas/CodeError' }
                components:
                  schemas:
                    CodeError: { type: object, properties: { codeMsg: { type: string } } }
                """;
        String original = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.5 }
                paths:
                  /things/{id}:
                    get:
                      responses:
                        '404':
                          description: Not Found
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/SpecError' }
                components:
                  schemas:
                    SpecError: { type: object, properties: { specMsg: { type: string } } }
                """;
        String out = new CorrectedSpecBuilder().withErrorSchemasFromSpec(corrected, original);

        assertThat(out).isEqualTo(corrected);       // the code's structured error body is untouched (no adoption)
        assertThat(out).doesNotContain("SpecError");   // the spec's error shape did NOT leak in
    }

    @Test
    void withErrorSchemasFromSpec_referencesEVERYMediaTypeOfAMultiMediaErrorBody() throws Exception {
        // pointSchemasAt guard: an error response can declare more than one media type. EVERY one must get the
        // reference — a loop that stopped after the first would leave a stale {type:object} the report would flag.
        String corrected = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.0 }
                paths:
                  /widgets:
                    get:
                      responses:
                        '500':
                          description: Internal Server Error
                          content:
                            application/problem+json: { schema: { type: object } }
                            application/json: { schema: { type: object } }
                components:
                  schemas: {}
                """;
        String original = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.5 }
                paths:
                  /widgets:
                    get:
                      responses:
                        '500':
                          description: Internal Server Error
                          content:
                            application/problem+json: { schema: { $ref: '#/components/schemas/error-model' } }
                components:
                  schemas:
                    error-model: { type: object, properties: { title: { type: string } } }
                """;
        String out = new CorrectedSpecBuilder().withErrorSchemasFromSpec(corrected, original);

        // BOTH media types of the 500 body reference error-model (not just the first).
        assertThat(errorSchemaRefsAt(out, "/widgets", "get", "500")).containsExactly("error-model", "error-model");
        assertThat(everyRefResolves(out)).isTrue();
    }

    @Test
    void withErrorSchemasFromSpec_dispatchesPerStatusAndLeavesUndocumentedStatusesGeneric() throws Exception {
        // Two guards: (a) per-status dispatch — 400 and 500 use DIFFERENT documented schemas, each must get ITS own;
        // (b) the canonical==null boundary — when the spec documents 2+ distinct error schemas there is no single
        // canonical fallback, so a corrected error status the spec never documented (418) must stay generic rather than
        // borrow an arbitrary one.
        String corrected = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.0 }
                paths:
                  /x:
                    get:
                      responses:
                        '400': { description: Bad Request, content: { application/problem+json: { schema: { type: object } } } }
                        '500': { description: Server Error, content: { application/problem+json: { schema: { type: object } } } }
                        '418': { description: Teapot, content: { application/problem+json: { schema: { type: object } } } }
                components:
                  schemas: {}
                """;
        String original = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.5 }
                paths:
                  /x:
                    get:
                      responses:
                        '400': { description: Bad Request, content: { application/json: { schema: { $ref: '#/components/schemas/bad-request' } } } }
                        '500': { description: Server Error, content: { application/json: { schema: { $ref: '#/components/schemas/server-error' } } } }
                components:
                  schemas:
                    bad-request: { type: object, properties: { field: { type: string } } }
                    server-error: { type: object, properties: { trace: { type: string } } }
                """;
        String out = new CorrectedSpecBuilder().withErrorSchemasFromSpec(corrected, original);

        assertThat(errorSchemaRefsAt(out, "/x", "get", "400")).containsExactly("bad-request");   // its OWN schema…
        assertThat(errorSchemaRefsAt(out, "/x", "get", "500")).containsExactly("server-error");  // …not a shared one
        assertThat(errorSchemaRefsAt(out, "/x", "get", "418")).containsExactly("<inline>");       // no canonical → generic
        assertThat(out).contains("bad-request:").contains("server-error:");   // both schemas copied into components
        assertThat(everyRefResolves(out)).isTrue();
    }

    @Test
    void withErrorSchemasFromSpec_leavesGenericSuccessBodiesUntouchedWhenACanonicalErrorSchemaExists() throws Exception {
        // The isErrorStatus guard is load-bearing: with a single canonical error schema, the per-status fallback would
        // otherwise apply it to ANY generic body — including a 2xx success. A generic 200 must stay generic.
        String corrected = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.0 }
                paths:
                  /x:
                    get:
                      responses:
                        '200': { description: OK, content: { application/json: { schema: { type: object } } } }
                        '500': { description: Server Error, content: { application/problem+json: { schema: { type: object } } } }
                components:
                  schemas: {}
                """;
        String original = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.5 }
                paths:
                  /x:
                    get:
                      responses:
                        '500': { description: Server Error, content: { application/json: { schema: { $ref: '#/components/schemas/error-model' } } } }
                components:
                  schemas:
                    error-model: { type: object, properties: { title: { type: string } } }
                """;
        String out = new CorrectedSpecBuilder().withErrorSchemasFromSpec(corrected, original);

        assertThat(errorSchemaRefsAt(out, "/x", "get", "500")).containsExactly("error-model");   // error enriched…
        assertThat(errorSchemaRefsAt(out, "/x", "get", "200")).containsExactly("<inline>");       // …success left generic
    }

    // ---- optional DRY structure: share identical responses via components/responses -------------------------------

    @Test
    void deduplicateResponses_hoistsResponsesRepeatedAcrossOperationsAndLeavesUniqueOnesInline() throws Exception {
        // Two operations declare the SAME 200 + 404 bodies inline; a hand-written spec (and the original) shares those
        // via components/responses. Hoist the duplicated ones to a single $ref referenced from both operations; a body
        // that DIFFERS across the operations (the 400 here) can't be shared and stays inline.
        String corrected = """
                openapi: 3.0.3
                info: { title: X, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/T' } } } }
                        '404': { description: Not Found, content: { application/problem+json: { schema: { type: object } } } }
                        '400': { description: Bad Request, content: { application/problem+json: { schema: { type: string } } } }
                  /b:
                    get:
                      responses:
                        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/T' } } } }
                        '404': { description: Not Found, content: { application/problem+json: { schema: { type: object } } } }
                        '400': { description: Bad Request, content: { application/problem+json: { schema: { type: integer } } } }
                components:
                  schemas:
                    T: { type: object }
                """;
        String out = new CorrectedSpecBuilder().deduplicateResponses(corrected);

        // 200 and 404 are identical across /a and /b → each hoisted once and referenced from BOTH operations.
        assertThat(responseRefAt(out, "/a", "get", "200")).isEqualTo("#/components/responses/200");
        assertThat(responseRefAt(out, "/b", "get", "200")).isEqualTo("#/components/responses/200");
        assertThat(responseRefAt(out, "/a", "get", "404")).isEqualTo("#/components/responses/404");
        assertThat(responseRefAt(out, "/b", "get", "404")).isEqualTo("#/components/responses/404");
        // The 400 differs between the two operations → NOT shared, stays inline (no $ref).
        assertThat(responseRefAt(out, "/a", "get", "400")).isNull();
        assertThat(responseRefAt(out, "/b", "get", "400")).isNull();
        // Exactly the duplicated statuses were hoisted into components/responses.
        assertThat(componentResponseKeys(out)).containsExactlyInAnyOrder("200", "404");
        assertThat(everyRefResolves(out)).isTrue();
        assertThat(new OpenApiModelExtractor().extract("corrected", out).parsed()).isTrue();
    }

    @Test
    void deduplicateResponses_returnsInputVerbatimWhenNothingIsDuplicated() {
        // A single operation (or all-distinct bodies) → nothing to share → the input is returned UNCHANGED, so the
        // guarded caller never risks a needless restructure.
        String corrected = "openapi: 3.0.3\ninfo: { title: X, version: 1.0.0 }\n"
                + "paths:\n  /a:\n    get:\n      responses:\n"
                + "        '404': { description: Not Found, content: { application/problem+json: { schema: { type: object } } } }\n";
        assertThat(new CorrectedSpecBuilder().deduplicateResponses(corrected)).isEqualTo(corrected);
    }

    /** True when every {@code $ref: #/components/schemas/X} in the doc points at a schema X actually defined under
     *  components/schemas — i.e. there are NO dangling references (the invalid-OpenAPI condition). */
    @SuppressWarnings("unchecked")
    private static boolean everyRefResolves(String yaml) throws Exception {
        Map<String, Object> root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readValue(yaml, Map.class);
        java.util.Set<String> defined = new java.util.HashSet<>();
        if (root.get("components") instanceof Map<?, ?> c
                && ((Map<String, Object>) c).get("schemas") instanceof Map<?, ?> s) {
            ((Map<String, Object>) s).keySet().forEach(k -> defined.add(String.valueOf(k)));
        }
        java.util.Set<String> refs = new java.util.HashSet<>();
        collectRefs(root, refs);
        return refs.stream().filter(r -> r.startsWith("#/components/schemas/"))
                .map(r -> r.substring("#/components/schemas/".length()))
                .allMatch(defined::contains);
    }

    @SuppressWarnings("unchecked")
    private static void collectRefs(Object node, java.util.Set<String> out) {
        if (node instanceof Map<?, ?> m) {
            if (((Map<String, Object>) m).get("$ref") instanceof String s) {
                out.add(s);
            }
            ((Map<String, Object>) m).values().forEach(v -> collectRefs(v, out));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collectRefs(v, out));
        }
    }

    /** For a given path+method+status response, the schema {@code $ref} name under EACH media type of its
     *  {@code content} (or {@code "<inline>"} when a media type's schema has no {@code $ref}). Empty when there is no
     *  content. Lets a test assert per-media-type and per-status enrichment precisely — a document-wide string search
     *  cannot tell WHICH media type / status got the reference. */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> errorSchemaRefsAt(String yaml, String path, String method, String status)
            throws Exception {
        Map<String, Object> root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readValue(yaml, Map.class);
        Map<String, Object> op = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) root.get("paths")).get(path)).get(method);
        Map<String, Object> resp = (Map<String, Object>) ((Map<String, Object>) op.get("responses")).get(status);
        java.util.List<String> refs = new java.util.ArrayList<>();
        if (resp == null || !(resp.get("content") instanceof Map<?, ?> byMedia)) {
            return refs;   // no content → nothing referenced (generic/empty)
        }
        for (Object media : ((Map<String, Object>) byMedia).values()) {
            Object schema = media instanceof Map<?, ?> mm ? ((Map<String, Object>) mm).get("schema") : null;
            Object ref = schema instanceof Map<?, ?> sm ? ((Map<String, Object>) sm).get("$ref") : null;
            refs.add(ref instanceof String s ? s.replace("#/components/schemas/", "") : "<inline>");
        }
        return refs;
    }

    /** The first media type's schema NODE of a given path+method+status response — for asserting the exact schema SHAPE
     *  (e.g. a {@code {error: $ref error-model}} wrapper vs a flattened top-level {@code $ref}). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaNodeAt(String yaml, String path, String method, String status)
            throws Exception {
        Map<String, Object> root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readValue(yaml, Map.class);
        Map<String, Object> op = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) root.get("paths")).get(path)).get(method);
        Map<String, Object> resp = (Map<String, Object>) ((Map<String, Object>) op.get("responses")).get(status);
        Map<String, Object> content = (Map<String, Object>) resp.get("content");
        Map<String, Object> media = (Map<String, Object>) content.values().iterator().next();
        return (Map<String, Object>) media.get("schema");
    }

    /** The {@code $ref} of a given path+method+status response (e.g. after de-duplication), or {@code null} when the
     *  response is still inline. */
    @SuppressWarnings("unchecked")
    private static String responseRefAt(String yaml, String path, String method, String status) throws Exception {
        Map<String, Object> root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readValue(yaml, Map.class);
        Map<String, Object> op = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) root.get("paths")).get(path)).get(method);
        Map<String, Object> resp = (Map<String, Object>) ((Map<String, Object>) op.get("responses")).get(status);
        Object ref = resp == null ? null : resp.get("$ref");
        return ref instanceof String s ? s : null;
    }

    /** The keys defined under {@code components/responses} (empty when absent) — to assert exactly which responses were
     *  hoisted for sharing. */
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> componentResponseKeys(String yaml) throws Exception {
        Map<String, Object> root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readValue(yaml, Map.class);
        Object comps = root.get("components");
        Object responses = comps instanceof Map<?, ?> c ? ((Map<String, Object>) c).get("responses") : null;
        return responses instanceof Map<?, ?> r ? new java.util.HashSet<>(((Map<String, Object>) r).keySet())
                : java.util.Set.of();
    }
}
