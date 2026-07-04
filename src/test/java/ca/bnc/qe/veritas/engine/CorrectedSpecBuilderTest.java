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
