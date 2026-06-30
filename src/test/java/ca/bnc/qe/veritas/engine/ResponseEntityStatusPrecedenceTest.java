package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Status-precedence gaps a deep review confirmed: with both @ResponseStatus and a ResponseEntity, Spring lets the
 * ResponseEntity status WIN (the extractor had it backwards); and a helper/factory-delegated ResponseEntity fabricated
 * a phantom 200 (false STATUS_CODE_MISSING) instead of an honest blind spot.
 */
class ResponseEntityStatusPrecedenceTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void responseEntityStatusWinsOverResponseStatusAnnotation(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                @ResponseStatus(HttpStatus.ACCEPTED)
                @PostMapping("/x") public ResponseEntity<String> create() {
                    return ResponseEntity.status(HttpStatus.CREATED).body("x");
                }
            }
            """);
        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));   // ResponseEntity wins
        assertThat(e.responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));  // @ResponseStatus loses
    }

    @Test
    void newResponseEntityConstructorStatusIsResolved(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.*;
            @RestController class C {
                @PostMapping("/x") public ResponseEntity<String> create() {
                    return new ResponseEntity<>("x", HttpStatus.CREATED);
                }
            }
            """);
        assertThat(only(new JavaSpringExtractor().extract(dir)).responses())
                .anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
    }

    @Test
    void helperDelegatedResponseEntitySurfacesABlindSpotNotAPhantom200(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                private Object factory;
                @PostMapping("/x") public ResponseEntity<String> create() { return factory.created("x"); }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(only(m).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }
}
