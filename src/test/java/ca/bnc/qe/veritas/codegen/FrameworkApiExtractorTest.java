package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Extracts the real WorldKey constants + public method signatures from framework sources (no LLM, no compile). */
class FrameworkApiExtractorTest {

    @Test
    void extractsWorldKeyConstantsAndPublicSignatures() throws Exception {
        Path root = Path.of(getClass().getClassLoader().getResource("fixtures/framework").toURI());

        Optional<String> block = new FrameworkApiExtractor().extract(root);

        assertThat(block).isPresent();
        assertThat(block.get())
                .contains("WorldKey constants:")
                .contains("RAW_RESPONSE").contains("ROBOT_TOKEN").contains("CONTEXT").contains("CLIENT_ID")
                .contains("RestClient:")
                .contains("Response get(String, String, String)")
                .contains("Response post(String, String, String, String)")
                .contains("getApiUrl(String, Map<String")   // generic preserved (spacing per JavaParser)
                .contains("Validate:")
                .contains("Objects.isNotNull(Object, String)")   // nested static path preserved
                .doesNotContain("internalHelper");   // private methods excluded
    }

    @Test
    void emptyWhenNoFrameworkSources() {
        assertThat(new FrameworkApiExtractor().extract(Path.of("does", "not", "exist"))).isEmpty();
        assertThat(new FrameworkApiExtractor().extract(null)).isEmpty();
    }
}
