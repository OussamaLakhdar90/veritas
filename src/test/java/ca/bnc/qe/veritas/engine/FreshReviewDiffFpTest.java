package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Diff-engine / extractor false positives a fresh deep review confirmed (each reproduced end-to-end):
 * a SAME-named oneOf/anyOf spec component bypassed the structureless-spec guard (false SCHEMA_FIELD_MISSING per field);
 * a byte[] DTO field was modelled as an array instead of a base64 string; a converter-bound param ("object") false-diffed
 * against a concrete spec scalar; and a code @Email (ConstraintSet.format) vs a conflicting spec format was dropped.
 */
class FreshReviewDiffFpTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static ApiModel spec(String yaml) {
        return new OpenApiModelExtractor().extract("spec", yaml).model();
    }

    // (#2 HIGH) A same-named oneOf/anyOf spec component is structureless-by-composition → field-diffing it against the
    // code DTO must be suppressed (it already is for the differently-named path).
    @Test
    void sameNamedCompositionSpecComponentDoesNotFalseFieldMissing(@TempDir Path dir) throws Exception {
        write(dir, "Payment.java", "public class Payment { public String id; public String amount; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/p") public Payment g() { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /p:
                get:
                  responses:
                    '200':
                      description: ok
                      content: { application/json: { schema: { $ref: '#/components/schemas/Payment' } } }
            components:
              schemas:
                Payment:
                  oneOf:
                    - $ref: '#/components/schemas/Card'
                    - $ref: '#/components/schemas/Bank'
                Card: { type: object, properties: { id: { type: string } } }
                Bank: { type: object, properties: { id: { type: string } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING);
    }

    // (#11 MED) A byte[] DTO field is a base64 string, not a JSON array → no array-vs-string field type mismatch.
    @Test
    void byteArrayFieldIsBase64StringNotArray(@TempDir Path dir) throws Exception {
        write(dir, "Document.java", "public class Document { public String id; public byte[] content; }");
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/doc") public Document g() { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /doc:
                get:
                  responses:
                    '200':
                      description: ok
                      content: { application/json: { schema: { $ref: '#/components/schemas/Document' } } }
            components:
              schemas:
                Document:
                  type: object
                  properties:
                    id:      { type: string }
                    content: { type: string, format: byte }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH
                        && f.getSummary() != null && f.getSummary().contains("content"));
    }

    // (#12 MED) A converter-bound param maps to the extractor's "object" unknown-marker → must be a wildcard, not a
    // false PARAM_TYPE_MISMATCH against a concrete spec scalar.
    @Test
    void converterBoundParamObjectTypeIsAWildcardNotAMismatch(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C {
                @GetMapping("/search") public Object search(@RequestParam("ref") ProductRef ref) { return null; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /search:
                get:
                  parameters: [ { name: ref, in: query, required: true, schema: { type: string } } ]
                  responses: { '200': { description: ok } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.PARAM_TYPE_MISMATCH);
    }

    // (#16 LOW) @Email stores its format in ConstraintSet.format — a conflicting spec format must surface (not drop).
    @Test
    void emailConstraintFormatVsConflictingSpecFormatIsReported(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import jakarta.validation.constraints.Email;
            @RestController class C {
                @GetMapping("/u") public String u(@RequestParam @Email String contact) { return "x"; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /u:
                get:
                  parameters: [ { name: contact, in: query, schema: { type: string, format: date } } ]
                  responses: { '200': { description: ok } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .anyMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                        && f.getSummary() != null && f.getSummary().contains("format"));
    }
}
