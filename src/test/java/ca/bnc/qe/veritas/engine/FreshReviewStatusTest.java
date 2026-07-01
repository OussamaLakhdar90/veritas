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

    // (#5b regression guard) The ubiquitous builder-then-body idiom `ResponseEntity.ok().body(x)` is a RESOLVABLE
    // ResponseEntity chain (root scope = ResponseEntity), NOT a helper delegation — it must resolve 200 with NO
    // "status could not be resolved" blind spot.
    @Test
    void builderChainOkBodyResolvesWithoutFalseBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                @GetMapping("/u") public ResponseEntity<String> g() { return ResponseEntity.ok().body("x"); }
                @PostMapping("/c") public ResponseEntity<String> c() {
                    return ResponseEntity.status(HttpStatus.CREATED).body("y");
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.endpoints()).anySatisfy(e -> assertThat(e.responses())
                .anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(200)));
        assertThat(m.endpoints()).anySatisfy(e -> assertThat(e.responses())
                .anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(201)));
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
    }

    // (dogfood: spring-boot-realworld-example-app) The dominant functional-Spring idiom
    // `repo.find(...).map(x -> ResponseEntity.ok(y)).orElseThrow(...)` builds the ResponseEntity INSIDE the .map lambda.
    // The return chain is rooted in a repository scope, but the status IS statically resolvable — allEntityStatuses
    // harvests the ResponseEntity.ok/noContent from the lambda — so it must resolve to 200/204 with NO false
    // "status could not be resolved" blind spot. (Before the fix, the non-ResponseEntity root scope alone tripped the
    // helper-delegation branch and emitted a false blind spot on every one of these — 7 of them on realworld.)
    @Test
    void mapLambdaResponseEntityChainResolvesWithoutFalseBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                private Optional<Object> repo;
                @GetMapping("/a") public ResponseEntity<?> article() {
                    return repo.map(a -> ResponseEntity.ok(a)).orElseThrow(RuntimeException::new);
                }
                @DeleteMapping("/d") public ResponseEntity<?> del() {
                    return repo.map(a -> ResponseEntity.noContent().build()).orElseThrow(RuntimeException::new);
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
        // GET resolves 200, DELETE resolves 204 — the statuses inside the .map lambda.
        assertThat(m.endpoints()).anySatisfy(e -> assertThat(e.responses())
                .anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(200)));
        assertThat(m.endpoints()).anySatisfy(e -> assertThat(e.responses())
                .anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(204)));
    }

    // (dogfood: spring-boot-realworld-example-app ProfileApi) An endpoint delegating to a LOCAL private ResponseEntity
    // helper — via a method reference (`.map(this::helper)`) and via a lambda body (`return helper(x)`) — where
    // `private ResponseEntity helper(..){ return ResponseEntity.ok(..); }` resolves to 200 through one-hop local
    // resolution, with NO false "status could not be resolved" blind spot.
    @Test
    void delegationToLocalResponseHelperResolvesWithoutFalseBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                private Optional<Object> repo;
                @GetMapping("/g") public ResponseEntity<?> g() {
                    return repo.map(this::ok200).orElseThrow(RuntimeException::new);
                }
                @PostMapping("/p") public ResponseEntity<?> p() {
                    return repo.map(x -> { return ok200(x); }).orElseThrow(RuntimeException::new);
                }
                private ResponseEntity ok200(Object o) { return ResponseEntity.ok(o); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.blindSpots().toString()).doesNotContain("status could not be resolved");
        assertThat(m.endpoints()).allSatisfy(e -> assertThat(e.responses())
                .anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(200)));
    }

    // (regression guard) A helper the endpoint delegates to that itself CANNOT be resolved (delegates onward to an
    // opaque factory) must keep the honest blind spot — one-hop local resolution must not paper over a real gap.
    @Test
    void delegationToUnresolvableLocalHelperKeepsBlindSpot(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            import java.util.Optional;
            @RestController class C {
                private Optional<Object> repo;
                private Object factory;
                @GetMapping("/g") public ResponseEntity<?> g() {
                    return repo.map(this::wrap).orElseThrow(RuntimeException::new);
                }
                private ResponseEntity wrap(Object o) { return factory.build(o); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
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
