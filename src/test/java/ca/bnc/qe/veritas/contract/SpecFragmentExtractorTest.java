package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpecFragmentExtractorTest {

    private static final String SPEC = """
            openapi: 3.0.0
            paths:
              /policies:
                get:
                  responses:
                    '200':
                      description: ok
              /policies/{id}:
                delete:
                  responses:
                    '204':
                      description: gone
            components: {}
            """;

    @Test
    void extractsThePathBlockPreservingIndentation() {
        String frag = SpecFragmentExtractor.extract(SPEC, "GET /policies");
        assertThat(frag).startsWith("  /policies:");
        assertThat(frag).contains("    get:");
        assertThat(frag).contains("description: ok");
        // stops at the next sibling path
        assertThat(frag).doesNotContain("/policies/{id}");
        assertThat(frag).doesNotContain("components");
    }

    @Test
    void handlesBarePathAndPathParams() {
        String frag = SpecFragmentExtractor.extract(SPEC, "/policies/{id}");
        assertThat(frag).startsWith("  /policies/{id}:");
        assertThat(frag).contains("delete:");
        assertThat(frag).doesNotContain("components");
    }

    @Test
    void returnsNullWhenPathAbsentOrInputBlank() {
        assertThat(SpecFragmentExtractor.extract(SPEC, "GET /nope")).isNull();
        assertThat(SpecFragmentExtractor.extract(null, "GET /policies")).isNull();
        assertThat(SpecFragmentExtractor.extract("{\"json\":true}", "GET /policies")).isNull();
    }
}
