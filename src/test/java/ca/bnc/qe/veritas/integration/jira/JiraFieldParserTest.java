package ca.bnc.qe.veritas.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The shared v2/v3 field parser: lifecycle from the stable status-category (and won't-do → DESCOPED), plus
 *  priority / labels / components / links, all tolerant of missing fields. */
class JiraFieldParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fields(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void lifecycleMapsTheStableStatusCategory() throws Exception {
        assertThat(JiraFieldParser.lifecycle(fields("{\"status\":{\"statusCategory\":{\"key\":\"done\"}}}"))).isEqualTo("DONE");
        assertThat(JiraFieldParser.lifecycle(fields("{\"status\":{\"statusCategory\":{\"key\":\"indeterminate\"}}}"))).isEqualTo("IN_PROGRESS");
        assertThat(JiraFieldParser.lifecycle(fields("{\"status\":{\"statusCategory\":{\"key\":\"new\"}}}"))).isEqualTo("TO_DO");
        assertThat(JiraFieldParser.lifecycle(fields("{}"))).isNull();   // no status fetched → no claim
    }

    @Test
    void aWontDoResolutionIsDescopedRegardlessOfCategory() throws Exception {
        // A "Won't Do" issue is usually status-category=done, but it is descoped, not done.
        assertThat(JiraFieldParser.lifecycle(fields(
                "{\"status\":{\"statusCategory\":{\"key\":\"done\"}},\"resolution\":{\"name\":\"Won't Do\"}}"))).isEqualTo("DESCOPED");
        assertThat(JiraFieldParser.lifecycle(fields(
                "{\"status\":{\"statusCategory\":{\"key\":\"done\"}},\"resolution\":{\"name\":\"Done\"}}"))).isEqualTo("DONE");
    }

    @Test
    void parsesPriorityLabelsComponentsAndLinks() throws Exception {
        JsonNode f = fields("{\"priority\":{\"name\":\"High\"},\"labels\":[\"policies\",\"  \"],"
                + "\"components\":[{\"name\":\"auth\"}],"
                + "\"issuelinks\":[{\"outwardIssue\":{\"key\":\"CIAM-9\"}},{\"inwardIssue\":{\"key\":\"CIAM-2\"}}]}");
        assertThat(JiraFieldParser.priority(f)).isEqualTo("High");
        assertThat(JiraFieldParser.labels(f)).containsExactly("policies");   // blank label dropped
        assertThat(JiraFieldParser.components(f)).containsExactly("auth");
        assertThat(JiraFieldParser.links(f)).containsExactly("CIAM-9", "CIAM-2");   // inward + outward
    }

    @Test
    void missingFieldsYieldNullOrEmpty() throws Exception {
        JsonNode f = fields("{}");
        assertThat(JiraFieldParser.priority(f)).isNull();
        assertThat(JiraFieldParser.labels(f)).isEmpty();
        assertThat(JiraFieldParser.components(f)).isEmpty();
        assertThat(JiraFieldParser.links(f)).isEmpty();
    }
}
