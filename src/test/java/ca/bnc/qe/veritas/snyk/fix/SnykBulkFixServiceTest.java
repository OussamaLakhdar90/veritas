package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.jira.JiraProject;
import ca.bnc.qe.veritas.integration.jira.JiraStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixRequest.AppSelection;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixRequest.IssueSelection;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixResult.AppResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the bulk fix orchestration: one epic + ONE shared story (chosen or created under the epic), and one train
 * per vulnerability with EVERY train linked to that story. A capturing fake {@link JiraClient} records the create
 * requests; the {@link AsyncSnykFixRunner} is mocked so we assert what trains would start without cloning anything.
 */
class SnykBulkFixServiceTest {

    private static ConnectionsProperties conns(String jiraBaseUrl) {
        ConnectionsProperties c = new ConnectionsProperties();
        c.getJira().setBaseUrl(jiraBaseUrl);
        return c;
    }

    private static final ConnectionsProperties CONNS = conns("https://jira.bnc.ca");

    /** Captures createIssue requests and hands back sequential keys; can be told to fail a create by summary. */
    private static final class CapturingJira implements JiraClient {
        final List<JiraCreateRequest> created = new ArrayList<>();
        final AtomicInteger seq = new AtomicInteger();
        String failForSummaryContaining;
        List<JiraProject> projects = new ArrayList<>(List.of(new JiraProject("CIAM", "CIAM Access")));
        RuntimeException listProjectsError;

        @Override public List<JiraIssue> search(String jql, List<String> fields, int maxResults) { return List.of(); }
        @Override public JiraIssue getIssue(String key) { return JiraIssue.basic(key, "", null); }
        @Override public JiraStatus getStatus(String key) { return new JiraStatus("", ""); }
        @Override public List<JiraProject> listProjects() {
            if (listProjectsError != null) {
                throw listProjectsError;
            }
            return projects;
        }
        @Override public String createIssue(JiraCreateRequest request) {
            if (failForSummaryContaining != null && request.summary() != null
                    && request.summary().contains(failForSummaryContaining)) {
                throw new IllegalStateException("Jira createIssue failed (simulated) for " + request.summary());
            }
            created.add(request);
            return "CIAM-" + (100 + seq.getAndIncrement());
        }
    }

    private static IssueSelection issue(String coord, String fixedIn) {
        return new IssueSelection("SNYK-" + coord, coord, "1.0.0", fixedIn, "critical");
    }

    private static AppSelection app(String appId, IssueSelection... issues) {
        return new AppSelection(appId, "watch-" + appId, List.of(issues));
    }

    /** Existing epic + existing story (nothing created). */
    private static SnykBulkFixRequest existingStory(String project, String epicKey, String storyKey,
                                                    List<AppSelection> apps) {
        return new SnykBulkFixRequest(project, epicKey, false, null, storyKey, false, null, List.of(), apps);
    }

    /** Existing epic + a NEW story created under it. */
    private static SnykBulkFixRequest newStory(String project, String epicKey, List<String> reviewers,
                                               List<AppSelection> apps) {
        return new SnykBulkFixRequest(project, epicKey, false, null, null, true, "Bump vulnerable deps", reviewers, apps);
    }

    @Test
    void createsEpicAndSharedStory_andLinksEveryTrainToThatStory() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        AtomicInteger t = new AtomicInteger();
        when(runner.submit(any())).thenAnswer(inv -> "train-" + t.getAndIncrement());

        SnykBulkFixRequest req = new SnykBulkFixRequest("CIAM", null, true, "Security remediation Q3",
                null, true, "Bump vulnerable deps", List.of("alice"),
                List.of(app("APP7576", issue("com.a:x", "2.0.0"), issue("com.b:y", "3.0.0")),
                        app("APP7571", issue("com.a:x", "2.0.0"))));

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner, CONNS).launch(req);

        // create[0] = Epic (no parent); create[1] = Story (under the epic). NO per-app Task.
        assertThat(jira.created.get(0).issueType()).isEqualTo("Epic");
        assertThat(jira.created.get(0).parentEpicKey()).isNull();
        assertThat(jira.created.get(1).issueType()).isEqualTo("Story");
        assertThat(jira.created.get(1).parentEpicKey()).isEqualTo(result.epicKey());
        assertThat(jira.created).noneMatch(r -> "Task".equals(r.issueType()));

        // 3 trains (2 + 1), ALL linked to the one shared story.
        assertThat(result.storyKey()).isNotBlank().isNotEqualTo(result.epicKey());
        assertThat(result.apps()).hasSize(2);
        assertThat(result.apps().get(0).trainIds()).hasSize(2);
        assertThat(result.apps().get(1).trainIds()).hasSize(1);

        ArgumentCaptor<SnykFixRequest> cap = ArgumentCaptor.forClass(SnykFixRequest.class);
        verify(runner, times(3)).submit(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(r -> {
            assertThat(r.jiraKey()).isEqualTo(result.storyKey());   // every fix lands on the ONE story
            assertThat(r.autoConfirm()).isTrue();
            assertThat(r.appIds()).hasSize(1);
        });
    }

    @Test
    void usesExistingEpicAndStory_andCreatesNoJiraIssues() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner, CONNS).launch(
                existingStory("CIAM", "CIAM-9", "CIAM-50", List.of(app("APP7576", issue("com.a:x", "2.0.0")))));

        assertThat(result.epicKey()).isEqualTo("CIAM-9");
        assertThat(result.storyKey()).isEqualTo("CIAM-50");
        assertThat(jira.created).isEmpty();   // existing epic + existing story → nothing created
        ArgumentCaptor<SnykFixRequest> cap = ArgumentCaptor.forClass(SnykFixRequest.class);
        verify(runner).submit(cap.capture());
        assertThat(cap.getValue().jiraKey()).isEqualTo("CIAM-50");
    }

    @Test
    void createsAStoryUnderAnExistingEpic() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner, CONNS).launch(
                newStory("CIAM", "CIAM-9", List.of(), List.of(app("APP7576", issue("com.a:x", "2.0.0")))));

        assertThat(result.epicKey()).isEqualTo("CIAM-9");
        assertThat(jira.created).singleElement().satisfies(r -> {
            assertThat(r.issueType()).isEqualTo("Story");
            assertThat(r.parentEpicKey()).isEqualTo("CIAM-9");   // the new story is filed under the existing epic
        });
    }

    @Test
    void rejectsWhenNoEpicAndNotAskedToCreateOne() {
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(new CapturingJira(), runner, CONNS).launch(
                new SnykBulkFixRequest("CIAM", null, false, null, null, true, "s", List.of(),
                        List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("epic");
        verify(runner, never()).submit(any());
    }

    @Test
    void rejectsWhenNoStoryAndNotAskedToCreateOne() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(jira, runner, CONNS).launch(
                new SnykBulkFixRequest("CIAM", "CIAM-9", false, null, null, false, null, List.of(),
                        List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("story");
        verify(runner, never()).submit(any());
    }

    @Test
    void rejectsUnsafeCoordinateBeforeAnyJiraWrite() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(jira, runner, CONNS).launch(
                existingStory("CIAM", "CIAM-9", "CIAM-50",
                        List.of(app("APP7576", new IssueSelection("s", "com.a:x && rm -rf", "1", "2", "high"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jira.created).isEmpty();   // fail-fast: nothing created
        verify(runner, never()).submit(any());
    }

    @Test
    void rejectsWhenProjectMissingOrNoIssues() {
        SnykBulkFixService svc = new SnykBulkFixService(new CapturingJira(), mock(AsyncSnykFixRunner.class), CONNS);
        assertThatThrownBy(() -> svc.launch(existingStory("", "CIAM-9", "CIAM-50",
                List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project");
        assertThatThrownBy(() -> svc.launch(existingStory("CIAM", "CIAM-9", "CIAM-50", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isolatesAppWhoseTrainSubmitFails_andStillLaunchesTheRest() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenAnswer(inv -> {
            SnykFixRequest r = inv.getArgument(0);
            if (r.appIds().contains("APP7571")) {
                throw new IllegalStateException("no Bitbucket project APP7571");
            }
            return "train-1";
        });

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner, CONNS).launch(
                existingStory("CIAM", "CIAM-9", "CIAM-50",
                        List.of(app("APP7576", issue("com.a:x", "2.0.0")), app("APP7571", issue("com.b:y", "3.0.0")))));

        AppResult ok = result.apps().stream().filter(a -> a.appId().equals("APP7576")).findFirst().orElseThrow();
        AppResult bad = result.apps().stream().filter(a -> a.appId().equals("APP7571")).findFirst().orElseThrow();
        assertThat(ok.jiraKey()).isEqualTo("CIAM-50");
        assertThat(ok.trainIds()).hasSize(1);
        assertThat(bad.jiraKey()).isNull();
        assertThat(bad.error()).isNotNull();
        assertThat(bad.trainIds()).isEmpty();
    }

    @Test
    void rejectsUnknownProjectBeforeAnyJiraWriteOrTrain() {
        CapturingJira jira = new CapturingJira();
        jira.projects = new ArrayList<>(List.of(new JiraProject("OTHER", "Other")));   // CIAM not accessible
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(jira, runner, CONNS).launch(
                existingStory("CIAM", "CIAM-9", "CIAM-50", List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CIAM");
        assertThat(jira.created).isEmpty();       // validated before any write
        verify(runner, never()).submit(any());    // nothing cloned or started
    }

    @Test
    void resolvesProjectKeyCaseInsensitivelyToItsCanonicalForm() {
        CapturingJira jira = new CapturingJira();   // accessible: "CIAM"
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");
        new SnykBulkFixService(jira, runner, CONNS).launch(
                newStory("ciam", "CIAM-9", List.of(), List.of(app("APP7576", issue("com.a:x", "2.0.0")))));   // typed lower-case
        // the new story is filed against the canonical "CIAM", not the typed "ciam"
        assertThat(jira.created).singleElement().satisfies(r -> assertThat(r.projectKey()).isEqualTo("CIAM"));
    }

    @Test
    void surfacesJiraConnectionErrorFromProjectValidationBeforeCloning() {
        CapturingJira jira = new CapturingJira();
        jira.listProjectsError = new IllegalStateException("Jira /project failed: 401 Unauthorized");
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(jira, runner, CONNS).launch(
                existingStory("CIAM", "CIAM-9", "CIAM-50", List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jira.created).isEmpty();
        verify(runner, never()).submit(any());    // connection verified up front → nothing cloned
    }

    @Test
    void buildsClickableJiraBrowseUrlsFromTheConfiguredBaseUrl() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");

        // Base URL carries a trailing slash to prove it's trimmed exactly once (no "//browse").
        SnykBulkFixResult result = new SnykBulkFixService(jira, runner, conns("https://jira.bnc.ca/")).launch(
                existingStory("CIAM", "CIAM-9", "CIAM-50", List.of(app("APP7576", issue("com.a:x", "2.0.0")))));

        assertThat(result.epicUrl()).isEqualTo("https://jira.bnc.ca/browse/CIAM-9");
        assertThat(result.storyUrl()).isEqualTo("https://jira.bnc.ca/browse/CIAM-50");
        assertThat(result.apps()).singleElement().satisfies(a -> {
            assertThat(a.jiraKey()).isEqualTo("CIAM-50");
            assertThat(a.jiraUrl()).isEqualTo("https://jira.bnc.ca/browse/CIAM-50");
        });
    }

    @Test
    void omitsBrowseUrlsWhenNoJiraBaseUrlIsConfigured() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner, conns(null)).launch(
                existingStory("CIAM", "CIAM-9", "CIAM-50", List.of(app("APP7576", issue("com.a:x", "2.0.0")))));

        assertThat(result.epicUrl()).isNull();
        assertThat(result.storyUrl()).isNull();
        assertThat(result.apps()).singleElement().satisfies(a -> assertThat(a.jiraUrl()).isNull());
    }

    @Test
    void filesAProfessionalStoryDescriptionThatExplainsScopeCascadeAndGovernance() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");

        // Two apps, three upgrades, mixed severities — the description must summarize this readably.
        new SnykBulkFixService(jira, runner, CONNS).launch(newStory("CIAM", "CIAM-9", List.of("alice"),
                List.of(app("APP7576",
                            new IssueSelection("s1", "com.a:x", "1.0.0", "2.0.0", "critical"),
                            new IssueSelection("s2", "com.b:y", "1.2.0", "1.3.0", "high")),
                        app("APP7571",
                            new IssueSelection("s3", "com.c:z", "3.0.0", "3.0.1", "medium")))));

        String body = String.join("\n", jira.created.get(0).descriptionParagraphs());
        // A real scope summary with counts + the cascade/gate + governance — not a bare bullet dump.
        assertThat(body)
                .contains("2 application")            // application count
                .contains("3 dependency")             // upgrade count
                .containsIgnoringCase("critical")     // severity breakdown
                .containsIgnoringCase("How this runs")   // the cascade + AI-gate explanation
                .containsIgnoringCase("Governance")      // the human-merge / never-auto-merge section
                .contains("APP7576").contains("com.a:x").contains("2.0.0");   // the concrete upgrades still listed
    }
}
