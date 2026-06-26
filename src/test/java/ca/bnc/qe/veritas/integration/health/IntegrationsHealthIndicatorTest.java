package ca.bnc.qe.veritas.integration.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.settings.ConnectionTestResult;
import ca.bnc.qe.veritas.settings.ConnectionTester;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class IntegrationsHealthIndicatorTest {

    private final ConnectionTester tester = mock(ConnectionTester.class);
    private final IntegrationsHealthIndicator indicator = new IntegrationsHealthIndicator(tester, true);

    private void stub(String service, boolean reachable, boolean authenticated, int status, String message) {
        when(tester.test(service)).thenReturn(new ConnectionTestResult(service, reachable, authenticated, status, message));
    }

    private void allNotConfigured() {
        for (String s : IntegrationsHealthIndicator.SERVICES) {
            stub(s, false, false, 0, "Not configured — set the base URL and token first.");
        }
    }

    @Test
    void upWhenEveryConfiguredDownstreamAuthenticates() {
        allNotConfigured();
        stub("jira", true, true, 200, "Connected as alice.");
        stub("bitbucket", true, true, 200, "Connected as alice.");

        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        var jira = (java.util.Map<String, Object>) h.getDetails().get("jira");
        assertThat(jira.get("state")).isEqualTo("UP");
    }

    @Test
    void downWhenAConfiguredDownstreamIsUnauthorized() {
        allNotConfigured();
        stub("jira", true, true, 200, "Connected as alice.");
        stub("bitbucket", true, false, 401, "token was rejected");   // reachable but not authenticated

        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        var bb = (java.util.Map<String, Object>) h.getDetails().get("bitbucket");
        assertThat(bb.get("state")).isEqualTo("DOWN");
    }

    @Test
    void unknownWhenNothingIsConfigured() {
        allNotConfigured();
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        @SuppressWarnings("unchecked")
        var jira = (java.util.Map<String, Object>) h.getDetails().get("jira");
        assertThat(jira.get("state")).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void reportsUpWithoutProbingWhenDisabled() {
        // Default (disabled): no network calls, never affects the default health aggregate.
        IntegrationsHealthIndicator disabled = new IntegrationsHealthIndicator(tester, false);
        Health h = disabled.health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails().get("probing")).asString().contains("disabled");
        org.mockito.Mockito.verifyNoInteractions(tester);
    }

    @Test
    void aProbeThatThrowsCountsAsDownNotACrash() {
        allNotConfigured();
        stub("jira", true, true, 200, "Connected as alice.");
        when(tester.test("bitbucket")).thenThrow(new RuntimeException("kaboom"));

        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        var bb = (java.util.Map<String, Object>) h.getDetails().get("bitbucket");
        assertThat((String) bb.get("message")).contains("kaboom");
    }
}
