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
 * Status-resolution false positives a discovery pass confirmed: a ResponseEntity.status(HttpStatus.X) for any of the ~46
 * HttpStatus values outside the old hardcoded 14-entry ladder (3xx redirects, 205/206/207, 405/410/429, …) collapsed to
 * a phantom 200 → false STATUS_CODE_MISSING(200) + the real status dropped; and a non-literal status(var) did the same.
 */
class JavaSpringExtractorStatusResolutionTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void redirectStatusResolvesToItsRealCodeNotAPhantom200(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                @GetMapping("/legacy")
                public ResponseEntity<Void> redirect() { return ResponseEntity.status(HttpStatus.SEE_OTHER).build(); }
            }
            """);
        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(303));
        assertThat(e.responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    }

    @Test
    void uncommonStatusesResolveThroughTheFullHttpStatusEnum(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                @GetMapping("/g") public ResponseEntity<String> g() { return ResponseEntity.status(HttpStatus.GONE).build(); }
            }
            """);
        assertThat(only(new JavaSpringExtractor().extract(dir)).responses())
                .anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(410));
    }

    @Test
    void nonLiteralStatusArgSurfacesABlindSpotNotAPhantom200(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            @RestController class C {
                @PostMapping("/u")
                public ResponseEntity<String> create() {
                    HttpStatus code = HttpStatus.ACCEPTED;
                    return ResponseEntity.status(code).body("x");
                }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        assertThat(only(m).responses()).noneSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(m.blindSpots().toString()).contains("status could not be resolved");
    }
}
