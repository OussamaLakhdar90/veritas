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
 * The app's Spring base path prefixes every route at runtime, and the spec side already folds in the OpenAPI
 * servers[].url base. The code side must apply it too — otherwise an app served under e.g. {@code /ciam} reports
 * phantom "missing from spec" + "dead spec" findings for the same endpoint (the WebFlux base-path bug).
 */
class JavaSpringExtractorBasePathTest {

    private static final String POLICY_CONTROLLER = """
            import org.springframework.web.bind.annotation.*;
            @RestController
            class PolicyController {
                @GetMapping({"/policies", "/policies/{app}"})
                public Object get() { return null; }
            }
            """;

    private static void writeJava(Path dir, String name, String content) throws Exception {
        Path java = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(java.resolve(name), content);
    }

    private static void writeConfig(Path dir, String name, String content) throws Exception {
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve(name), content);
    }

    @Test
    void appliesWebfluxBasePathToCodeEndpoints(@TempDir Path dir) throws Exception {
        writeConfig(dir, "application.yml", "spring:\n  webflux:\n    base-path: /ciam\n");
        writeJava(dir, "PolicyController.java", POLICY_CONTROLLER);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        assertThat(model.endpoints()).extracting(Endpoint::signature)
                .containsExactlyInAnyOrder("GET /ciam/policies", "GET /ciam/policies/{app}");
    }

    @Test
    void appliesServletContextPathFromProperties(@TempDir Path dir) throws Exception {
        writeConfig(dir, "application.properties", "server.servlet.context-path=/api\n");
        writeJava(dir, "OwnerController.java", """
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/owners")
            class OwnerController {
                @GetMapping("/{id}")
                public Object get() { return null; }
            }
            """);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        assertThat(model.endpoints()).extracting(Endpoint::signature).containsExactly("GET /api/owners/{id}");
    }

    @Test
    void noBasePathLeavesPathsUnchanged(@TempDir Path dir) throws Exception {
        writeConfig(dir, "application.yml", "server:\n  port: 8080\n");   // no base path configured
        writeJava(dir, "PolicyController.java", POLICY_CONTROLLER);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        assertThat(model.endpoints()).extracting(Endpoint::signature)
                .containsExactlyInAnyOrder("GET /policies", "GET /policies/{app}");
    }

    @Test
    void skipsTemplatedBasePathPlaceholder(@TempDir Path dir) throws Exception {
        // An unresolved ${...} placeholder can't be matched statically — must NOT be prepended literally.
        writeConfig(dir, "application.yml", "spring:\n  webflux:\n    base-path: ${API_BASE:/ciam}\n");
        writeJava(dir, "PolicyController.java", POLICY_CONTROLLER);

        ApiModel model = new JavaSpringExtractor().extract(dir);

        assertThat(model.endpoints()).extracting(Endpoint::signature)
                .containsExactlyInAnyOrder("GET /policies", "GET /policies/{app}");
    }
}
