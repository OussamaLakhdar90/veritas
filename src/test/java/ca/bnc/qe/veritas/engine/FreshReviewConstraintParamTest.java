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
 * Constraint / param false positives a fresh deep review confirmed: a @RequestParam MultipartFile was emitted as a
 * phantom QUERY param (false PARAM_MISSING + REQUEST_BODY_PRESENCE_MISMATCH); a constant-valued @Pattern emitted the
 * constant's NAME as the pattern; and a constant-valued @Min alongside a literal @Max produced a "code=null" gap.
 */
class FreshReviewConstraintParamTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static ApiModel spec(String yaml) {
        return new OpenApiModelExtractor().extract("spec", yaml).model();
    }

    // (#13 MED) @RequestParam MultipartFile is a multipart body part, not a query param.
    @Test
    void requestParamMultipartFileIsABodyNotAQueryParam(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.multipart.MultipartFile;
            @RestController class C {
                @PostMapping("/avatars") public String upload(@RequestParam("file") MultipartFile file) { return "ok"; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /avatars:
                post:
                  requestBody:
                    content:
                      multipart/form-data:
                        schema: { type: object, properties: { file: { type: string, format: binary } } }
                  responses: { '200': { description: ok } }
            """);
        var findings = new DiffEngine().diffCodeVsSpec(code, spec);
        assertThat(findings).noneMatch(f -> f.getType() == FindingType.PARAM_MISSING);
        assertThat(findings).noneMatch(f -> f.getType() == FindingType.REQUEST_BODY_PRESENCE_MISMATCH);
    }

    // (#7 MED) A constant-valued @Pattern can't be resolved to a literal regex → it must not emit the constant name
    // (or a "code=null") as a false pattern CONSTRAINT_GAP. (@Size keeps the constraint set non-empty so the diff runs.)
    @Test
    void constantValuedPatternDoesNotFalseGap(@TempDir Path dir) throws Exception {
        write(dir, "Dto.java", """
            import jakarta.validation.constraints.*;
            class Dto {
                public static final String PHONE = "^\\\\+?[0-9]{10}$";
                @Pattern(regexp = PHONE) @Size(min = 1) public String phone;
            }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/x") public Dto x(@RequestBody Dto d) { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /x:
                post:
                  requestBody:
                    content: { application/json: { schema: { $ref: '#/components/schemas/Dto' } } }
                  responses: { '200': { description: ok } }
            components:
              schemas:
                Dto: { type: object, properties: { phone: { type: string, minLength: 1, pattern: '^\\+?[0-9]{10}$' } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                        && f.getSummary() != null && f.getSummary().contains("pattern"));
    }

    // (#8 MED) A constant-valued @Min degrades to a null bound; a co-present literal @Max must not expose it as a false
    // "minimum code=null" CONSTRAINT_GAP against a spec that documents the (real) minimum.
    @Test
    void constantValuedMinWithLiteralMaxDoesNotFalseNullGap(@TempDir Path dir) throws Exception {
        write(dir, "Dto.java", """
            import jakarta.validation.constraints.*;
            class Dto {
                public static final int MIN_AGE = 18;
                @Min(MIN_AGE) @Max(120) public Integer age;
            }
            """);
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/x") public Dto x(@RequestBody Dto d) { return null; } }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /x:
                post:
                  requestBody:
                    content: { application/json: { schema: { $ref: '#/components/schemas/Dto' } } }
                  responses: { '200': { description: ok } }
            components:
              schemas:
                Dto: { type: object, properties: { age: { type: integer, minimum: 18, maximum: 120 } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                        && f.getSummary() != null && f.getSummary().contains("code=null"));
    }
}
