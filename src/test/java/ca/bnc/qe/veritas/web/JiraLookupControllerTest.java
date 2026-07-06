package ca.bnc.qe.veritas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
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

    /** Captures createIssue calls + the story-search JQL, and returns canned lookups. */
    private static final class FakeJira implements JiraClient {
        JiraCreateRequest lastCreate;
        String lastSearchJql;
        List<JiraProject> projects = List.of();
        List<JiraIssue> epics = List.of();
        List<JiraIssue> stories = List.of();

        @Override public List<JiraIssue> search(String jql, List<String> fields, int maxResults) {
            this.lastSearchJql = jql;
            return stories;
        }
        @Override public JiraIssue getIssue(String key) { return JiraIssue.basic(key, "", null); }
        @Override public JiraStatus getStatus(String key) { return new JiraStatus("", ""); }
        @Override public List<JiraProject> listProjects() { return projects; }
        @Override public List<JiraIssue> listEpics(String projectKey, int max) { return epics; }
        @Override public String createIssue(JiraCreateRequest request) {
            this.lastCreate = request;
            return "CIAM-500";
        }
    }

    /** Controller wired with a CLOUD edition (so the story JQL uses {@code parent =}, no create-meta needed). */
    private static JiraLookupController controller(FakeJira jira) {
        ConnectionsProperties conns = new ConnectionsProperties();
        conns.getJira().setEdition("CLOUD");
        return new JiraLookupController(jira, conns);
    }

    @Test
    void projectsPassThrough() {
        FakeJira jira = new FakeJira();
        jira.projects = List.of(new JiraProject("CIAM", "CIAM Access"), new JiraProject("APP", "App"));
        assertThat(controller(jira).projects())
                .extracting(JiraProject::key).containsExactly("CIAM", "APP");
    }

    @Test
    void epicsMapToKeyAndSummary() {
        FakeJira jira = new FakeJira();
        jira.epics = List.of(JiraIssue.basic("CIAM-1", "Security remediation Q3", null),
                JiraIssue.basic("CIAM-2", "Upgrade jackson", null));
        List<JiraLookupController.EpicOption> epics = controller(jira).epics("CIAM");
        assertThat(epics).extracting(JiraLookupController.EpicOption::key).containsExactly("CIAM-1", "CIAM-2");
        assertThat(epics).extracting(JiraLookupController.EpicOption::summary)
                .containsExactly("Security remediation Q3", "Upgrade jackson");
    }

    @Test
    void createEpicBuildsEpicIssueTypeRequestAndReturnsKey() {
        FakeJira jira = new FakeJira();
        Map<String, String> out = controller(jira).createEpic(
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
        controller(jira).createEpic(
                new JiraLookupController.CreateEpicRequest("CIAM", "Only summary", "  "));
        assertThat(jira.lastCreate.descriptionParagraphs()).isEmpty();
    }

    @Test
    void createEpicRejectsBlankProjectOrSummaryOrNullBody() {
        JiraLookupController c = controller(new FakeJira());
        assertThatThrownBy(() -> c.createEpic(new JiraLookupController.CreateEpicRequest("", "s", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.createEpic(new JiraLookupController.CreateEpicRequest("CIAM", " ", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.createEpic(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void epicStoriesListOpenChildrenAndMapKeySummary() {
        FakeJira jira = new FakeJira();
        jira.stories = List.of(JiraIssue.basic("CIAM-10", "Fix jackson in app7576", null),
                JiraIssue.basic("CIAM-11", "Fix commons-lang", null));
        List<JiraLookupController.StoryOption> stories = controller(jira).epicStories("CIAM-1");
        assertThat(stories).extracting(JiraLookupController.StoryOption::key).containsExactly("CIAM-10", "CIAM-11");
        assertThat(stories).extracting(JiraLookupController.StoryOption::summary)
                .containsExactly("Fix jackson in app7576", "Fix commons-lang");
        // CLOUD edition → children matched via `parent = "EPIC"`, filtered to open (statusCategory != Done).
        assertThat(jira.lastSearchJql).contains("parent = \"CIAM-1\"").contains("statusCategory != Done");
    }
}
