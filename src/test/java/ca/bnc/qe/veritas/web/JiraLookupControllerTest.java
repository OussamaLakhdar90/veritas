package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.jira.JiraProject;
import ca.bnc.qe.veritas.integration.jira.JiraStatus;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JiraLookupController} — the project/epic pickers + create-epic that back the Snyk bulk-fix
 * flow. A capturing fake {@link JiraClient} asserts the controller wiring (mapping, validation, and the exact
 * {@code Epic} issue-type request it builds) without standing up HTTP.
 */
class JiraLookupControllerTest {

    /** Captures createIssue calls and returns canned lookups. */
    private static final class FakeJira implements JiraClient {
        JiraCreateRequest lastCreate;
        List<JiraProject> projects = List.of();
        List<JiraIssue> epics = List.of();

        @Override public List<JiraIssue> search(String jql, List<String> fields, int maxResults) { return List.of(); }
        @Override public JiraIssue getIssue(String key) { return JiraIssue.basic(key, "", null); }
        @Override public JiraStatus getStatus(String key) { return new JiraStatus("", ""); }
        @Override public List<JiraProject> listProjects() { return projects; }
        @Override public List<JiraIssue> listEpics(String projectKey, int max) { return epics; }
        @Override public String createIssue(JiraCreateRequest request) {
            this.lastCreate = request;
            return "CIAM-500";
        }
    }

    @Test
    void projectsPassThrough() {
        FakeJira jira = new FakeJira();
        jira.projects = List.of(new JiraProject("CIAM", "CIAM Access"), new JiraProject("APP", "App"));
        assertThat(new JiraLookupController(jira).projects())
                .extracting(JiraProject::key).containsExactly("CIAM", "APP");
    }

    @Test
    void epicsMapToKeyAndSummary() {
        FakeJira jira = new FakeJira();
        jira.epics = List.of(JiraIssue.basic("CIAM-1", "Security remediation Q3", null),
                JiraIssue.basic("CIAM-2", "Upgrade jackson", null));
        List<JiraLookupController.EpicOption> epics = new JiraLookupController(jira).epics("CIAM");
        assertThat(epics).extracting(JiraLookupController.EpicOption::key).containsExactly("CIAM-1", "CIAM-2");
        assertThat(epics).extracting(JiraLookupController.EpicOption::summary)
                .containsExactly("Security remediation Q3", "Upgrade jackson");
    }

    @Test
    void createEpicBuildsEpicIssueTypeRequestAndReturnsKey() {
        FakeJira jira = new FakeJira();
        Map<String, String> out = new JiraLookupController(jira).createEpic(
                new JiraLookupController.CreateEpicRequest("CIAM", "Security remediation", "batch of Snyk fixes"));
        assertThat(out).containsEntry("key", "CIAM-500");
        assertThat(jira.lastCreate.projectKey()).isEqualTo("CIAM");
        assertThat(jira.lastCreate.issueType()).isEqualTo("Epic");
        assertThat(jira.lastCreate.summary()).isEqualTo("Security remediation");
        assertThat(jira.lastCreate.descriptionParagraphs()).containsExactly("batch of Snyk fixes");
    }

    @Test
    void createEpicWithNoDescriptionSendsEmptyParagraphs() {
        FakeJira jira = new FakeJira();
        new JiraLookupController(jira).createEpic(
                new JiraLookupController.CreateEpicRequest("CIAM", "Only summary", "  "));
        assertThat(jira.lastCreate.descriptionParagraphs()).isEmpty();
    }

    @Test
    void createEpicRejectsBlankProjectOrSummaryOrNullBody() {
        JiraLookupController c = new JiraLookupController(new FakeJira());
        assertThatThrownBy(() -> c.createEpic(new JiraLookupController.CreateEpicRequest("", "s", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.createEpic(new JiraLookupController.CreateEpicRequest("CIAM", " ", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.createEpic(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
