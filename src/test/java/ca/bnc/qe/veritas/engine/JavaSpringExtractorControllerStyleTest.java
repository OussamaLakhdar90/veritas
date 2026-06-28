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
 * Found by stress-testing extraction on adversarial real projects (macrozheng/mall, an all-Kotlin app, a functional
 * WebFlux module). Two coverage gaps the extractor used to hit SILENTLY (empty model, no blind spot):
 *   1. classic Spring MVC REST — a plain {@code @Controller} whose REST-ness is per-method {@code @ResponseBody}
 *      (mall: 0/243 endpoints before this) — now extracted; view handlers (no @ResponseBody) stay excluded.
 *   2. Kotlin controllers and functional {@code RouterFunction} routing the Java/annotation extractor can't read —
 *      now surfaced as honest blind spots instead of an unexplained empty model.
 */
class JavaSpringExtractorControllerStyleTest {

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void extractsControllerWithPerMethodResponseBody(@TempDir Path dir) throws Exception {
        write(dir, "BrandController.java", """
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.*;
            @Controller
            @RequestMapping("/brand")
            class BrandController {
                @RequestMapping(value = "/listAll", method = RequestMethod.GET)
                @ResponseBody
                public Object listAll() { return null; }

                @RequestMapping(value = "/page", method = RequestMethod.GET)
                public String page() { return "brandPage"; }   // returns a VIEW — not an API endpoint
            }
            """);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        // The @ResponseBody handler is a REST endpoint; the view handler (no @ResponseBody) is excluded.
        assertThat(model.endpoints()).extracting(Endpoint::signature).containsExactly("GET /brand/listAll");
    }

    @Test
    void pureMvcControllerWithNoResponseBodyYieldsNoEndpoints(@TempDir Path dir) throws Exception {
        write(dir, "PageController.java", """
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.*;
            @Controller
            @RequestMapping("/pages")
            class PageController {
                @GetMapping("/home") public String home() { return "home"; }   // view only
            }
            """);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        // No @ResponseBody anywhere → not a REST controller → no fabricated endpoints.
        assertThat(model.endpoints()).isEmpty();
    }

    @Test
    void declaresBlindSpotForKotlinControllers(@TempDir Path dir) throws Exception {
        write(dir, "UserController.kt", """
            @RestController
            @RequestMapping("/api/users")
            class UserController {
                @GetMapping("/me") fun me(): Any? = null
            }
            """);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        assertThat(model.endpoints()).isEmpty();
        assertThat(model.blindSpots()).anySatisfy(b -> assertThat(b).contains("Kotlin").contains("not analysed"));
    }

    @Test
    void declaresBlindSpotForFunctionalRouting(@TempDir Path dir) throws Exception {
        write(dir, "Routes.java", """
            import org.springframework.web.reactive.function.server.*;
            class Routes {
                RouterFunction<ServerResponse> routes() {
                    return RouterFunctions.route(null, null);
                }
            }
            """);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        assertThat(model.blindSpots()).anySatisfy(b -> assertThat(b).contains("Functional routing").contains("not analysed"));
    }
}
