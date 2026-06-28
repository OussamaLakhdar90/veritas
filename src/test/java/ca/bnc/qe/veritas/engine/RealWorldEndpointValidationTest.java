package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import org.junit.jupiter.api.Test;

/**
 * All-endpoints validation against a real GitHub project with conventional controllers
 * (gothinkster/spring-boot-realworld-example-app — the RealWorld "Conduit" API). The expected set is the hand-verified
 * ground truth from the source: 19 endpoints across 8 controllers, including the two that use method-level
 * {@code @RequestMapping(method=...)} rather than the {@code @*Mapping} shorthands (UsersApi, the CommentsApi DELETE).
 * Veritas must extract exactly these — no misses, no fabrications. Guarded by {@code -Ddogfood.root} so CI skips it.
 */
class RealWorldEndpointValidationTest {

    /** Every endpoint in the RealWorld API, verified by reading each controller's class + method mappings. */
    private static final String[] EXPECTED = {
            "GET /articles/{slug}", "PUT /articles/{slug}", "DELETE /articles/{slug}",
            "POST /articles/{slug}/favorite", "DELETE /articles/{slug}/favorite",
            "POST /articles", "GET /articles/feed", "GET /articles",
            "POST /articles/{slug}/comments", "GET /articles/{slug}/comments",
            "DELETE /articles/{slug}/comments/{id}",
            "GET /user", "PUT /user",
            "GET /profiles/{username}", "POST /profiles/{username}/follow", "DELETE /profiles/{username}/follow",
            "GET /tags",
            "POST /users", "POST /users/login",   // method-level @RequestMapping(method=POST), not @PostMapping
    };

    @Test
    void extractsEveryRealWorldEndpoint() throws Exception {
        String root = System.getProperty("dogfood.root");
        assumeTrue(root != null, "set -Ddogfood.root to the clones dir");
        Path src = Path.of(root, "spring-boot-realworld-example-app", "src", "main", "java");
        assumeTrue(Files.isDirectory(src), "clone spring-boot-realworld-example-app first");

        ApiModel model = new JavaSpringExtractor().extract(src);

        // 100% precision + recall: exactly the 19 ground-truth endpoints, nothing missed, nothing invented.
        assertThat(model.endpoints()).extracting(Endpoint::signature)
                .containsExactlyInAnyOrder(EXPECTED);
    }
}
