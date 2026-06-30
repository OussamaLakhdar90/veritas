package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Status-machinery blind spots a fresh deep review confirmed: a controller-local specific @ExceptionHandler was scored
 * MEDIUM (counted) on every sibling, including non-throwing ones; a method mixing a resolvable and an unresolvable
 * ResponseEntity return dropped the unresolvable status with no blind spot; 3xx redirect statuses were never compared;
 * and an @ExceptionHandler with both @ResponseStatus and a ResponseEntity resolved to the wrong (annotation) status.
 */
class FreshReviewStatusTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static ApiModel spec(String yaml) {
        return new OpenApiModelExtractor().extract("spec", yaml).model();
    }

    // (#4 MED) A local specific @ExceptionHandler attached to a non-throwing sibling must be LOW (manual review), not a
    // scored MEDIUM STATUS_CODE_MISSING.
    @Test
    void localExceptionHandlerStatusOnSiblingIsLowNotScored(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            @RestController @RequestMapping("/orders") class C {
                @GetMapping public String list() { return "x"; }
                @ExceptionHandler(IllegalStateException.class)
                @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
                public String handle() { return "err"; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /orders: { get: { responses: { '200': { description: ok } } } }
            """);
        var findings = new DiffEngine().diffCodeVsSpec(code, spec);
        assertThat(findings).noneMatch(f -> f.getType() == FindingType.STATUS_CODE_MISSING
                && f.getSummary() != null && f.getSummary().contains("402") && f.getConfidence() != Confidence.LOW);
    }

    // (#5 MED) A method mixing a resolvable return and an unresolvable delegated return must surface a blind spot.
    @Test
    void mixedResolvableAndUnresolvableReturnSurfacesBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                private Object factory;
                @GetMapping("/x") public ResponseEntity<String> get(@RequestParam boolean cond) {
                    if (cond) { return ResponseEntity.ok("x"); }
                    return factory.notFound();
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }

    // (#6 MED) A code 3xx redirect the spec omits must be compared, not dropped in the band gap between 2xx and 4xx.
    @Test
    void codeRedirectStatusMissingFromSpecIsReported(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                @GetMapping("/legacy") public ResponseEntity<Void> moved() {
                    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).build();
                }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = spec("""
            openapi: 3.0.1
            info: { title: t, version: '1' }
            paths:
              /legacy: { get: { responses: { '200': { description: ok } } } }
            """);
        assertThat(new DiffEngine().diffCodeVsSpec(code, spec))
                .anyMatch(f -> f.getType() == FindingType.STATUS_CODE_MISSING
                        && f.getSummary() != null && f.getSummary().contains("301"));
    }

    // (#15 LOW) An @ExceptionHandler with BOTH @ResponseStatus and a ResponseEntity must resolve to the ResponseEntity's
    // in-body status (SPR-30305), not the annotation's.
    @Test
    void exceptionHandlerResponseEntityStatusWinsOverResponseStatusAnnotation(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @GetMapping("/x") public String x() { return "x"; } }
            """);
        write(dir, "A.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestControllerAdvice class A {
                @ExceptionHandler(IllegalStateException.class)
                @ResponseStatus(HttpStatus.I_AM_A_TEAPOT)
                public ResponseEntity<String> h() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body("e"); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints().get(0).responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(404));
        assertThat(m.endpoints().get(0).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(418));
    }
}
