package ca.bnc.qe.veritas.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfluenceStorageToMarkdownTest {

    private final ConfluenceStorageToMarkdown conf = new ConfluenceStorageToMarkdown();

    @Test
    void convertsStorageXhtmlIncludingCodeMacroAndTable() {
        String xhtml = """
            <h1>Policy API</h1>
            <p>The service manages <strong>policies</strong>.</p>
            <h2>Acceptance Criteria</h2>
            <ul><li>User must provide a valid name</li><li>Code length must be 10 or less</li></ul>
            <table><tbody>
              <tr><th>Field</th><th>Rule</th></tr>
              <tr><td>name</td><td>required</td></tr>
            </tbody></table>
            <ac:structured-macro ac:name="code">
              <ac:parameter ac:name="language">json</ac:parameter>
              <ac:plain-text-body><![CDATA[{"x":1}]]></ac:plain-text-body>
            </ac:structured-macro>
            """;
        NormalizedDoc doc = conf.normalize("CONF-1", "Policy API", xhtml);
        String md = doc.markdown();

        assertThat(md).contains("# Policy API");
        assertThat(md).contains("**policies**");
        assertThat(md).contains("## Acceptance Criteria");
        assertThat(md).contains("- User must provide a valid name");
        assertThat(md).contains("| Field | Rule |");
        assertThat(md).contains("| name | required |");
        assertThat(md).contains("```json");
        assertThat(md).contains("{\"x\":1}");
        // storage macro/markup scaffolding must be gone
        assertThat(md).doesNotContain("ac:structured-macro");
    }
}
