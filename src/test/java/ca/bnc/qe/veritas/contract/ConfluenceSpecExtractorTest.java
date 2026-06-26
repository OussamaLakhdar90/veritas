package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfluenceSpecExtractorTest {

    @Test
    void nullBodyThrowsIllegalState() {
        assertThatThrownBy(() -> ConfluenceSpecExtractor.extractSpec(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confluence page body is empty");
    }

    @Test
    void blankBodyThrowsIllegalState() {
        assertThatThrownBy(() -> ConfluenceSpecExtractor.extractSpec("   \n\t  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confluence page body is empty");
    }

    @Test
    void pageWithNoCodeBlockThrows() {
        String xhtml = "<p>Just some prose, no macros and no code at all.</p>";
        assertThatThrownBy(() -> ConfluenceSpecExtractor.extractSpec(xhtml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No code block found");
    }

    @Test
    void pageWithOnlyBlankCodeBlockThrows() {
        // The <pre> exists but contains only whitespace -> skipped -> best stays null.
        String xhtml = "<pre>   \n   </pre>";
        assertThatThrownBy(() -> ConfluenceSpecExtractor.extractSpec(xhtml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No code block found");
    }

    @Test
    void extractsFromPlainTextBodyCdataMacroPreservingIndentation() {
        String xhtml = """
                <ac:structured-macro ac:name="code">
                  <ac:plain-text-body><![CDATA[openapi: 3.0.0
                paths:
                  /policies:
                    get:
                      responses:
                        '200':
                          description: ok]]></ac:plain-text-body>
                </ac:structured-macro>
                """;
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).startsWith("openapi: 3.0.0");
        // YAML indentation is preserved verbatim.
        assertThat(spec).contains("  /policies:");
        assertThat(spec).contains("    get:");
        assertThat(spec).contains("description: ok");
        // Result is stripped then a single trailing newline appended.
        assertThat(spec).endsWith("description: ok\n");
    }

    @Test
    void extractsFromPreTagWhenNoMacroPresent() {
        String xhtml = "<pre>openapi: 3.1.0\npaths: {}</pre>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).isEqualTo("openapi: 3.1.0\npaths: {}\n");
    }

    @Test
    void extractsFromCodeTagWhenNoMacroOrPrePresent() {
        String xhtml = "<code>swagger: \"2.0\"\npaths:\n  /x: {}</code>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).startsWith("swagger: \"2.0\"");
        assertThat(spec).endsWith("/x: {}\n");
    }

    @Test
    void specLikeBlockBeatsNonSpecBlockOfSameOrGreaterLength() {
        // A long shell snippet (score 0) and a short real spec (score 3 from "openapi").
        String longNoise = "curl -s https://example.com/very/long/url/that/is/quite/lengthy "
                + "| jq '.data' | grep -v error | sort | uniq | head -n 50";
        String xhtml = "<pre>" + longNoise + "</pre>"
                + "<pre>openapi: 3.0.0</pre>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).isEqualTo("openapi: 3.0.0\n");
        assertThat(spec).doesNotContain("curl");
    }

    @Test
    void jsonPathsKeyContributesToScoreAndWins() {
        // JSON-style spec scores on the "paths" key; a plain block scores 0.
        String json = "{ \"openapi\": \"3.0.0\", \"paths\": { \"/a\": {} } }";
        String xhtml = "<pre>some unrelated text block here</pre>"
                + "<code>" + json.replace("\"", "&quot;") + "</code>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).contains("\"openapi\"");
        assertThat(spec).contains("\"paths\"");
        assertThat(spec).doesNotContain("unrelated text");
    }

    @Test
    void higherScoringOpenapiBlockBeatsLowerScoringSwaggerBlock() {
        // First block: swagger (+2). Second block: openapi (+3) -> second wins on score.
        String xhtml = "<pre>swagger: \"2.0\"\ninfo: {}</pre>"
                + "<pre>openapi: 3.0.0\ninfo: {}</pre>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).startsWith("openapi: 3.0.0");
        assertThat(spec).doesNotContain("swagger");
    }

    @Test
    void tieOnScoreIsBrokenByLongerBlock() {
        // Both blocks score the same (each contains "openapi" only). The longer one wins.
        String shortBlock = "openapi: 3.0.0";
        String longBlock = "openapi: 3.0.0\ninfo:\n  title: Big Long Spec With More Bytes Here\n  version: 1.0.0";
        String xhtml = "<pre>" + shortBlock + "</pre>" + "<pre>" + longBlock + "</pre>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).contains("Big Long Spec");
        assertThat(spec).contains("version: 1.0.0");
    }

    @Test
    void tieByLengthKeepsFirstWhenSecondIsNotStrictlyLonger() {
        // Two equal-score, equal-length blocks: the second is NOT strictly longer,
        // so the first encountered block is retained.
        String xhtml = "<pre>openapi-AAA</pre>" + "<pre>openapi-BBB</pre>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).isEqualTo("openapi-AAA\n");
    }

    @Test
    void wholeTextIgnoresNestedElementChildrenButKeepsDirectText() {
        // The <pre> has a direct text node and a nested <span> element; only the
        // direct text node is concatenated by wholeText (CDataNode/TextNode only).
        String xhtml = "<pre>openapi: 3.0.0<span>ignored-nested</span></pre>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).contains("openapi: 3.0.0");
        assertThat(spec).doesNotContain("ignored-nested");
    }

    @Test
    void resultIsStrippedOfSurroundingWhitespaceAndGetsSingleTrailingNewline() {
        String xhtml = "<pre>\n\n   openapi: 3.0.0   \n\n</pre>";
        String spec = ConfluenceSpecExtractor.extractSpec(xhtml);
        assertThat(spec).isEqualTo("openapi: 3.0.0\n");
    }
}
