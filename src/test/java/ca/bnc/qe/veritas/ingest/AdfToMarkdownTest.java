package ca.bnc.qe.veritas.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AdfToMarkdownTest {

    private final AdfToMarkdown adf = new AdfToMarkdown(new ObjectMapper());

    @Test
    void convertsHeadingsBoldListsAndTables() {
        String json = """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Acceptance Criteria"}]},
              {"type":"paragraph","content":[
                {"type":"text","text":"The service manages "},
                {"type":"text","text":"policies","marks":[{"type":"strong"}]}]},
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"User must provide a name"}]}]},
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Code length <= 10"}]}]}]},
              {"type":"table","content":[
                {"type":"tableRow","content":[
                  {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Field"}]}]},
                  {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Rule"}]}]}]},
                {"type":"tableRow","content":[
                  {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"name"}]}]},
                  {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"required"}]}]}]}]}
            ]}
            """;
        NormalizedDoc doc = adf.normalize("JIRA-1", "Policy", json);
        String md = doc.markdown();

        assertThat(md).contains("## Acceptance Criteria");
        assertThat(md).contains("The service manages **policies**");
        assertThat(md).contains("- User must provide a name");
        assertThat(md).contains("| Field | Rule |");
        assertThat(md).contains("| name | required |");
        // raw ADF JSON noise must be gone
        assertThat(md).doesNotContain("\"type\"");
    }
}
