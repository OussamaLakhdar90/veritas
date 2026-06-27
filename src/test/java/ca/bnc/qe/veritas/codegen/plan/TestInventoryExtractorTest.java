package ca.bnc.qe.veritas.codegen.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The static scan of an existing test project: quoted path literals + nearby verb, skipping noise and build dirs. */
class TestInventoryExtractorTest {

    private final TestInventoryExtractor extractor = new TestInventoryExtractor();

    private static final String POLICY_TEST = """
            package ca.bnc.tests;
            class PolicyTest {
                String schema = "/schemas/policy.json";   // a resource file, not an endpoint
                String root = "/";                          // not a route
                void create() {
                    given().contentType("application/json").body(payload)
                        .when().post("/policies")
                        .then().statusCode(201);
                }
                void get() {
                    given().pathParam("id", id)
                        .when().get("/policies/{id}")
                        .then().statusCode(200);
                }
            }
            """;

    @Test
    void scansQuotedPathLiteralsAndInfersTheVerb(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("PolicyTest.java"), POLICY_TEST);

        TestInventory inv = extractor.scan(dir);

        assertThat(inv.filesScanned()).isEqualTo(1);
        assertThat(inv.references())
                .anySatisfy(r -> {
                    assertThat(r.method()).isEqualTo(HttpMethod.POST);
                    assertThat(r.path()).isEqualTo("/policies");
                    assertThat(r.sourceFile()).isEqualTo("PolicyTest.java");
                })
                .anySatisfy(r -> {
                    assertThat(r.method()).isEqualTo(HttpMethod.GET);
                    assertThat(r.path()).isEqualTo("/policies/{id}");
                });
        // The resource-file literal and the bare root are not mistaken for endpoints.
        assertThat(inv.references()).extracting(TestReference::path)
                .doesNotContain("/schemas/policy.json", "/");
    }

    @Test
    void missingOrEmptyDirYieldsAnEmptyInventory(@TempDir Path dir) {
        assertThat(extractor.scan(dir.resolve("does-not-exist")).isEmpty()).isTrue();
        assertThat(extractor.scan(dir).isEmpty()).isTrue();   // empty dir
    }

    @Test
    void buildOutputDirectoriesAreSkipped(@TempDir Path dir) throws IOException {
        Path generated = dir.resolve("target").resolve("generated");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("Junk.java"), "x.get(\"/should-be-ignored\");");
        Files.writeString(dir.resolve("RealTest.java"), "x.get(\"/policies\");");

        TestInventory inv = extractor.scan(dir);

        assertThat(inv.filesScanned()).isEqualTo(1);   // only RealTest.java, not the target/ file
        assertThat(inv.references()).extracting(TestReference::path)
                .containsExactly("/policies")
                .doesNotContain("/should-be-ignored");
    }
}
