package ca.bnc.qe.veritas.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.jira.AdfBuilder;
import ca.bnc.qe.veritas.integration.jira.JiraCloudClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.xray.XrayCloudClient;
import ca.bnc.qe.veritas.integration.xray.XrayStep;
import ca.bnc.qe.veritas.integration.xray.XrayTestSpec;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JiraXrayBuildersTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of("x");

    @Test
    void adfWrapsParagraphs() {
        JsonNode adf = AdfBuilder.doc(List.of("Actual: 500", "Expected: 200"));
        assertThat(adf.get("type").asText()).isEqualTo("doc");
        assertThat(adf.get("content")).hasSize(2);
        assertThat(adf.get("content").get(0).get("content").get(0).get("text").asText()).isEqualTo("Actual: 500");
    }

    @Test
    void jiraCreatePayloadHasProjectTypeSummaryAndAdf() throws Exception {
        ConnectionsProperties props = new ConnectionsProperties();
        props.getJira().setBaseUrl("https://jira.bnc");
        JiraCloudClient jira = new JiraCloudClient(props, secrets, mapper,
                new Retries(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build()));
        String payload = jira.buildCreatePayload(new JiraCreateRequest(
                "CIAM", "Bug", "ciam-policies — POST /transfers — missing endpoint",
                List.of("Actual vs expected", "Code: PolicyController.java:45"), List.of("contract-validation")));
        JsonNode node = mapper.readTree(payload);
        assertThat(node.path("fields").path("project").path("key").asText()).isEqualTo("CIAM");
        assertThat(node.path("fields").path("issuetype").path("name").asText()).isEqualTo("Bug");
        assertThat(node.path("fields").path("summary").asText()).contains("missing endpoint");
        assertThat(node.path("fields").path("description").path("type").asText()).isEqualTo("doc");
        assertThat(node.path("fields").path("labels").get(0).asText()).isEqualTo("contract-validation");
    }

    @Test
    void xrayQueryBuildersAreWellFormed() {
        ConnectionsProperties props = new ConnectionsProperties();
        props.getXray().setBaseUrl("https://xray.cloud.getxray.app");
        XrayCloudClient xray = new XrayCloudClient(props, secrets, mapper,
                new Retries(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build()));

        String q = xray.buildGetTestsQuery("key = CIAM-1");
        assertThat(q).contains("getTests(jql:");
        assertThat(q).contains("limit: 100");
        assertThat(q).contains("steps { action data result }");

        String m = xray.buildCreateTestMutation(new XrayTestSpec("CIAM", "Validate create policy", "Manual",
                List.of(new XrayStep("POST /policies", "{...}", "201"))));
        assertThat(m).contains("createTest(testType: {name: \"Manual\"}");
        assertThat(m).contains("summary: \"Validate create policy\"");
        assertThat(m).contains("project: {key: \"CIAM\"}");
    }
}
