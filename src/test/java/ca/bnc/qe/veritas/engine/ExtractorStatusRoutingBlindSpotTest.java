package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Routing/status blind spots a discovery pass confirmed: a 2+ level meta-annotation chain to a class @RequestMapping
 * dropped the base path (phantom-root endpoints); and an error status a controller produces via its OWN throw or a
 * controller-LOCAL @ExceptionHandler was never scanned (extractAdvice is gated to @ControllerAdvice).
 */
class ExtractorStatusRoutingBlindSpotTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    private static Endpoint only(ApiModel m) {
        assertThat(m.endpoints()).hasSize(1);
        return m.endpoints().get(0);
    }

    @Test
    void twoLevelMetaAnnotationChainResolvesTheClassBasePath(@TempDir Path dir) throws Exception {
        write(dir, "ApiBase.java", """
            import org.springframework.web.bind.annotation.RequestMapping;
            @RequestMapping("/base") public @interface ApiBase {}
            """);
        write(dir, "ApiV1.java", "@ApiBase public @interface ApiV1 {}");
        write(dir, "MetaController.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController @ApiV1
            public class MetaController { @GetMapping("/thing") public String thing() { return "x"; } }
            """);
        assertThat(only(new JavaSpringExtractor().extract(dir)).pathTemplate()).isEqualTo("/base/thing");
    }

    @Test
    void controllerOwnThrowOfAResponseStatusExceptionIsCaptured(@TempDir Path dir) throws Exception {
        write(dir, "DuplicateSkuException.java", """
            import org.springframework.web.bind.annotation.ResponseStatus;
            import org.springframework.http.HttpStatus;
            @ResponseStatus(HttpStatus.CONFLICT)
            public class DuplicateSkuException extends RuntimeException {}
            """);
        write(dir, "ProductController.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController class ProductController {
                @PostMapping("/products")
                public String create(@RequestBody String p) {
                    if (p.isEmpty()) throw new DuplicateSkuException();
                    return p;
                }
            }
            """);
        Endpoint e = only(new JavaSpringExtractor().extract(dir));
        assertThat(e.responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(409));
    }

    @Test
    void controllerLocalExceptionHandlerStatusIsCaptured(@TempDir Path dir) throws Exception {
        write(dir, "OrderController.java", """
            import org.springframework.web.bind.annotation.*;
            import org.springframework.http.HttpStatus;
            @RestController @RequestMapping("/orders")
            class OrderController {
                @GetMapping("/{id}") public String get(@PathVariable String id) { return id; }

                @ExceptionHandler(IllegalStateException.class)
                @ResponseStatus(HttpStatus.NOT_FOUND)
                public String handle(IllegalStateException e) { return "nf"; }
            }
            """);
        ApiModel m = new JavaSpringExtractor().extract(dir);
        Endpoint get = m.endpoints().stream().filter(e -> e.method().name().equals("GET")).findFirst().orElseThrow();
        assertThat(get.responses()).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(404));
    }
}
