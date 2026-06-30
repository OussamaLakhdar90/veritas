package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression guards for the 6 defects the campaign's adversarial review caught (each was a NEW false positive / silent
 * drop my own blind-spot fixes introduced): allOf-scalar-leaf flatten, local catch-all confidence, multipart
 * double-fire. (denyAll security, alias suppression, and scalar-name collision are locked in their own test files.)
 */
class CampaignRegressionTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void allOfOfAScalarEnumLeafIsSurfacedAsAComposition_notFlattenedToEmpty() {
        ApiModel m = new OpenApiModelExtractor().extract("spec", """
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths: {}
            components:
              schemas:
                AccountState: { type: string, enum: [OPEN, FROZEN, CLOSED] }
                Account:
                  allOf:
                    - $ref: '#/components/schemas/AccountState'
            """).model();
        // Account is allOf:[$ref <enum leaf>] — must NOT be flattened to an empty object (which would drop the enum and
        // false-diff). It is surfaced as an unmodelled composition blind spot.
        assertThat(m.blindSpots().toString()).contains("'Account'").contains("composition");
    }

    @Test
    void localCatchAllExceptionHandlerStatusIsLowNotMedium(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            @RestController @RequestMapping("/orders")
            class C {
                @GetMapping("/{id}") public String get(@PathVariable String id) { return id; }

                @ExceptionHandler(Exception.class)
                @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                public String handle(Exception e) { return "err"; }
            }
            """);
        Endpoint get = new JavaSpringExtractor().extract(dir).endpoints().stream()
                .filter(e -> e.method().name().equals("GET")).findFirst().orElseThrow();
        ResponseModel r500 = get.responses().stream().filter(r -> r.statusCode() == 500).findFirst().orElseThrow();
        // a catch-all (Exception) local handler is a BLANKET error → blanket-LOW origin, like a global @ControllerAdvice
        assertThat(r500.origin()).isEqualTo("EXCEPTION_HANDLER_GLOBAL");
    }

    @Test
    void multipartConsumesMismatchFiresExactlyOnce(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.multipart.MultipartFile;
            import org.springframework.http.MediaType;
            @RestController class C {
                @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
                public String up(@RequestPart("file") MultipartFile file) { return "ok"; }
            }
            """);
        ApiModel code = new JavaSpringExtractor().extract(dir);
        ApiModel spec = new OpenApiModelExtractor().extract("spec", """
            openapi: 3.0.1
            info: { title: t, version: '1.0' }
            paths:
              /upload:
                post:
                  requestBody:
                    content: { application/json: { schema: { type: object } } }
                  responses: { '200': { description: ok } }
            """).model();
        long n = new DiffEngine().diffCodeVsSpec(code, spec).stream()
                .filter(f -> f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH).count();
        assertThat(n).isEqualTo(1);   // was 2 (consumes + request-body-content both fired)
    }
}
