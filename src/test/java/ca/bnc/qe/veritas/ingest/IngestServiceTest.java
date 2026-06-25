package ca.bnc.qe.veritas.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Verifies the fetch→normalize→extract pipeline with stub clients (no HTTP) — fully local. */
class IngestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private IngestService service(JiraClient jira, ConfluenceClient confluence) {
        return new IngestService(jira, confluence, new AdfToMarkdown(mapper),
                new ConfluenceStorageToMarkdown(), new TestBasisExtractor());
    }

    @Test
    void buildsTestBasisFromJiraAdf() throws Exception {
        String adfJson = """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Acceptance Criteria"}]},
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"The policy is created"}]}]}]}
            ]}
            """;
        JiraClient jira = new JiraClient() {
            public List<JiraIssue> search(String jql, List<String> fields, int max) {
                try {
                    return List.of(JiraIssue.basic("CIAM-100", "Create policy", mapper.readTree(adfJson)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            public JiraIssue getIssue(String key) {
                return null;
            }
            public String createIssue(ca.bnc.qe.veritas.integration.jira.JiraCreateRequest request) {
                return null;
            }
            public ca.bnc.qe.veritas.integration.jira.JiraStatus getStatus(String key) {
                return null;
            }
        };

        TestBasis basis = service(jira, null).fromJira("project = CIAM", 50);

        assertThat(basis.items()).anyMatch(i -> i.kind() == TestBasisKind.REQUIREMENT
                && i.text().equals("Create policy") && i.id().equals("CIAM-100#summary"));
        assertThat(basis.items()).anyMatch(i -> i.kind() == TestBasisKind.ACCEPTANCE_CRITERIA
                && i.text().contains("policy is created") && i.id().startsWith("CIAM-100#"));
    }

    @Test
    void buildsTestBasisFromConfluenceStorage() {
        String xhtml = "<h2>Rules</h2><ul><li>A policy name must be unique</li></ul>";
        ConfluenceClient confluence = pageId -> new ConfluencePage(pageId, "Policy rules", xhtml);

        TestBasis basis = service(null, confluence).fromConfluence(List.of("12345"));

        assertThat(basis.items()).anyMatch(i -> i.kind() == TestBasisKind.BUSINESS_RULE
                && i.text().contains("name must be unique") && i.id().startsWith("12345#"));
    }
}
