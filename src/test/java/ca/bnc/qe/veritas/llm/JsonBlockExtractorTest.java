package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JsonBlockExtractorTest {

    private final JsonBlockExtractor extractor = new JsonBlockExtractor();

    @Test
    void takesLastFencedJsonBlock() {
        String raw = """
                Here is some reasoning.
                ```json
                {"draft": true}
                ```
                And the final answer:
                ```json
                {"message": "done"}
                ```
                """;
        assertThat(extractor.extract(raw)).isEqualTo("{\"message\": \"done\"}");
    }

    @Test
    void fallsBackToOutermostBraces() {
        String raw = "no fences here, just {\"message\": \"hi\"} inline";
        assertThat(extractor.extract(raw)).isEqualTo("{\"message\": \"hi\"}");
    }

    @Test
    void throwsWhenNoJson() {
        assertThatThrownBy(() -> extractor.extract("nothing useful here"))
                .isInstanceOf(IllegalStateException.class);
    }
}
