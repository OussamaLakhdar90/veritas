package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraTransition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The Jira side of a fix: robust transition matching + a facts-carrying ticket. */
class SnykFixJiraServiceTest {

    private final JiraClient jira = mock(JiraClient.class);
    private final SnykFixJiraService service = new SnykFixJiraService(jira);

    private SnykFixTrain train() {
        SnykFixTrain t = new SnykFixTrain();
        t.setCoordinate("com.fasterxml.jackson.core:jackson-databind");
        t.setOldVersion("3.1.1");
        t.setFixedIn("2.15.0");
        t.setSeverity("critical");
        t.setAppIds("app7576,app7571");
        return t;
    }

    @Test
    void transitionMatchesTheWorkflowNameAndFires() {
        when(jira.listTransitions("CIAM-1")).thenReturn(List.of(
                new JiraTransition("21", "In Progress"), new JiraTransition("31", "Done")));
        service.transitionTo("CIAM-1", SnykFixJiraService.Phase.IN_PROGRESS);
        verify(jira).transition("CIAM-1", "21");
    }

    @Test
    void transitionSkipsWhenNoMatchingWorkflowState() {
        when(jira.listTransitions("CIAM-1")).thenReturn(List.of(new JiraTransition("21", "Start work")));
        service.transitionTo("CIAM-1", SnykFixJiraService.Phase.IN_REVIEW);
        verify(jira, never()).transition(any(), any());   // no "review" transition → left unchanged, no throw
    }

    @Test
    void transitionNeverThrowsOnAJiraError() {
        when(jira.listTransitions("CIAM-1")).thenThrow(new IllegalStateException("Jira down"));
        service.transitionTo("CIAM-1", SnykFixJiraService.Phase.DONE);   // must not propagate
    }

    @Test
    void usesAProvidedTicketWithoutCreatingOne() {
        String key = service.ensureTicket(train(), "CIAM-99", "CIAM", "Task", null);
        assertThat(key).isEqualTo("CIAM-99");
        verify(jira, never()).createIssue(any());
    }

    @Test
    void createsATicketCarryingTheFacts() {
        when(jira.createIssue(any())).thenReturn("CIAM-100");
        BreakingVerdict verdict = new BreakingVerdict(true, true, 90, List.of("major bump"), "adapt X");
        String key = service.ensureTicket(train(), null, "CIAM", "Task", verdict);
        assertThat(key).isEqualTo("CIAM-100");
        ArgumentCaptor<JiraCreateRequest> cap = ArgumentCaptor.forClass(JiraCreateRequest.class);
        verify(jira).createIssue(cap.capture());
        String desc = String.join("\n", cap.getValue().descriptionParagraphs());
        assertThat(desc).contains("jackson-databind").contains("CRITICAL")
                .contains("app7576").contains("BREAKING");
        assertThat(cap.getValue().summary()).contains("jackson-databind").contains("2.15.0");
    }

    @Test
    void doneMatchesClosedOrResolveWorkflows() {
        when(jira.listTransitions("CIAM-1")).thenReturn(List.of(new JiraTransition("41", "Resolve Issue")));
        service.transitionTo("CIAM-1", SnykFixJiraService.Phase.DONE);
        verify(jira).transition(eq("CIAM-1"), eq("41"));
    }
}
