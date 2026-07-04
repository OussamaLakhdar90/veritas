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
 * Verifies the bulk fix orchestration: one epic, one ticket per app filed under it, and one train per vulnerability
 * with every train linked to its app's ticket. A capturing fake {@link JiraClient} records the create requests; the
 * {@link AsyncSnykFixRunner} is mocked so we assert what trains would start without cloning anything.
 */
class SnykBulkFixServiceTest {

    /** Captures createIssue requests and hands back sequential keys; can be told to fail one app's ticket. */
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

    @Test
    void createsEpicThenOneTicketPerAppUnderIt_andLinksEveryTrainToItsAppTicket() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        AtomicInteger t = new AtomicInteger();
        when(runner.submit(any())).thenAnswer(inv -> "train-" + t.getAndIncrement());

        SnykBulkFixRequest req = new SnykBulkFixRequest("CIAM", null, true, "Security remediation Q3", List.of("alice"),
                List.of(app("APP7576", issue("com.a:x", "2.0.0"), issue("com.b:y", "3.0.0")),
                        app("APP7571", issue("com.a:x", "2.0.0"))));

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner).launch(req);

        // First create is the epic (type Epic, no parent); then one Task per app, each filed under the epic.
        assertThat(jira.created.get(0).issueType()).isEqualTo("Epic");
        assertThat(jira.created.get(0).parentEpicKey()).isNull();
        String epicKey = result.epicKey();
        assertThat(jira.created).filteredOn(r -> "Task".equals(r.issueType()))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.parentEpicKey()).isEqualTo(epicKey));

        // Two app tickets, 3 trains (2 + 1), each linked to its app's ticket and run straight through.
        assertThat(result.apps()).hasSize(2);
        assertThat(result.apps().get(0).trainIds()).hasSize(2);
        assertThat(result.apps().get(1).trainIds()).hasSize(1);

        ArgumentCaptor<SnykFixRequest> cap = ArgumentCaptor.forClass(SnykFixRequest.class);
        verify(runner, times(3)).submit(cap.capture());
        for (SnykFixRequest r : cap.getAllValues()) {
            assertThat(r.jiraKey()).startsWith("CIAM-");
            assertThat(r.autoConfirm()).isTrue();
            assertThat(r.appIds()).hasSize(1);
        }
        // The two trains for the first app share ONE ticket key.
        String app0Ticket = result.apps().get(0).jiraKey();
        assertThat(cap.getAllValues().subList(0, 2)).allSatisfy(r -> assertThat(r.jiraKey()).isEqualTo(app0Ticket));
    }

    @Test
    void usesExistingEpicWhenKeyProvided_andCreatesNoEpic() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner).launch(
                new SnykBulkFixRequest("CIAM", "CIAM-9", false, null, List.of(),
                        List.of(app("APP7576", issue("com.a:x", "2.0.0")))));

        assertThat(result.epicKey()).isEqualTo("CIAM-9");
        assertThat(jira.created).noneMatch(r -> "Epic".equals(r.issueType()));
        assertThat(jira.created).singleElement()
                .satisfies(r -> assertThat(r.parentEpicKey()).isEqualTo("CIAM-9"));
    }

    @Test
    void rejectsWhenNoEpicAndNotAskedToCreateOne() {
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(new CapturingJira(), runner).launch(
                new SnykBulkFixRequest("CIAM", null, false, null, List.of(),
                        List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("epic");
        verify(runner, never()).submit(any());
    }

    @Test
    void rejectsUnsafeCoordinateBeforeAnyJiraWrite() {
        CapturingJira jira = new CapturingJira();
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(jira, runner).launch(
                new SnykBulkFixRequest("CIAM", "CIAM-9", false, null, List.of(),
                        List.of(app("APP7576", new IssueSelection("s", "com.a:x && rm -rf", "1", "2", "high"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jira.created).isEmpty();   // fail-fast: no epic, no tickets
        verify(runner, never()).submit(any());
    }

    @Test
    void rejectsWhenProjectMissingOrNoIssues() {
        SnykBulkFixService svc = new SnykBulkFixService(new CapturingJira(), mock(AsyncSnykFixRunner.class));
        assertThatThrownBy(() -> svc.launch(new SnykBulkFixRequest("", "CIAM-9", false, null, List.of(),
                List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project");
        assertThatThrownBy(() -> svc.launch(new SnykBulkFixRequest("CIAM", "CIAM-9", false, null, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isolatesAppWhoseTicketFails_andStillLaunchesTheRest() {
        CapturingJira jira = new CapturingJira();
        jira.failForSummaryContaining = "APP7571";
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        AtomicInteger t = new AtomicInteger();
        when(runner.submit(any())).thenAnswer(inv -> "train-" + t.getAndIncrement());

        SnykBulkFixResult result = new SnykBulkFixService(jira, runner).launch(
                new SnykBulkFixRequest("CIAM", "CIAM-9", false, null, List.of(),
                        List.of(app("APP7576", issue("com.a:x", "2.0.0")),
                                app("APP7571", issue("com.b:y", "3.0.0")))));

        AppResult ok = result.apps().stream().filter(a -> a.appId().equals("APP7576")).findFirst().orElseThrow();
        AppResult bad = result.apps().stream().filter(a -> a.appId().equals("APP7571")).findFirst().orElseThrow();
        assertThat(ok.jiraKey()).isNotNull();
        assertThat(ok.trainIds()).hasSize(1);
        assertThat(bad.jiraKey()).isNull();
        assertThat(bad.error()).isNotNull();
        assertThat(bad.trainIds()).isEmpty();
        verify(runner, times(1)).submit(any());   // only the surviving app's train
    }

    @Test
    void rejectsUnknownProjectBeforeAnyJiraWriteOrTrain() {
        CapturingJira jira = new CapturingJira();
        jira.projects = new ArrayList<>(List.of(new JiraProject("OTHER", "Other")));   // CIAM not accessible
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(jira, runner).launch(
                new SnykBulkFixRequest("CIAM", "CIAM-9", false, null, List.of(),
                        List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CIAM");
        assertThat(jira.created).isEmpty();       // no epic, no app ticket — validated before any write
        verify(runner, never()).submit(any());    // nothing cloned or started
    }

    @Test
    void resolvesProjectKeyCaseInsensitivelyToItsCanonicalForm() {
        CapturingJira jira = new CapturingJira();   // accessible: "CIAM"
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        when(runner.submit(any())).thenReturn("train-1");
        new SnykBulkFixService(jira, runner).launch(
                new SnykBulkFixRequest("ciam", "CIAM-9", false, null, List.of(),   // typed lower-case
                        List.of(app("APP7576", issue("com.a:x", "2.0.0")))));
        // the app ticket is filed against the canonical "CIAM", not the typed "ciam"
        assertThat(jira.created).singleElement().satisfies(r -> assertThat(r.projectKey()).isEqualTo("CIAM"));
    }

    @Test
    void surfacesJiraConnectionErrorFromProjectValidationBeforeCloning() {
        CapturingJira jira = new CapturingJira();
        jira.listProjectsError = new IllegalStateException("Jira /project failed: 401 Unauthorized");
        AsyncSnykFixRunner runner = mock(AsyncSnykFixRunner.class);
        assertThatThrownBy(() -> new SnykBulkFixService(jira, runner).launch(
                new SnykBulkFixRequest("CIAM", "CIAM-9", false, null, List.of(),
                        List.of(app("APP7576", issue("com.a:x", "2.0.0"))))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jira.created).isEmpty();
        verify(runner, never()).submit(any());    // connection verified up front → nothing cloned
    }
}
