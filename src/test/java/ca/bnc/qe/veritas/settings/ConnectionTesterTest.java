package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.vcs.GitHost;
import org.junit.jupiter.api.Test;

class ConnectionTesterTest {

    private final JiraClient jira = mock(JiraClient.class);
    private final GitHost git = mock(GitHost.class);
    private final ConfluenceClient confluence = mock(ConfluenceClient.class);
    private final CopilotAuthService copilot = mock(CopilotAuthService.class);

    private ConnectionTester tester(Map<String, String> secretMap, String jiraBaseUrl) {
        ConnectionsProperties props = new ConnectionsProperties();
        props.getJira().setBaseUrl(jiraBaseUrl);
        SecretProvider secrets = key -> Optional.ofNullable(secretMap.get(key));
        return new ConnectionTester(jira, git, confluence, copilot, props, secrets);
    }

    @Test
    void jiraSuccessIsReachableAndAuthenticated() {
        when(jira.whoAmI()).thenReturn("Alice QA");
        ConnectionTestResult r = tester(Map.of("JIRA_API_TOKEN", "pat"), "https://jira.bnc").test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isTrue();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.message()).contains("Alice QA");
    }

    @Test
    void jira401IsReachableButNotAuthenticated() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Jira /myself failed: 401 Unauthorized"));
        ConnectionTestResult r = tester(Map.of("JIRA_API_TOKEN", "bad"), "https://jira.bnc").test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.message()).contains("token was rejected");
    }

    @Test
    void unreachableHostIsClassifiedNotReachable() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Jira /myself failed: I/O error: Connection refused"));
        ConnectionTestResult r = tester(Map.of("JIRA_API_TOKEN", "pat"), "https://jira.bnc").test("jira");
        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("Could not reach");
    }

    @Test
    void notConfiguredWhenTokenMissing() {
        ConnectionTestResult r = tester(Map.of(), "https://jira.bnc").test("jira");   // base set, no token
        assertThat(r.reachable()).isFalse();
        assertThat(r.status()).isZero();
        assertThat(r.message()).contains("Not configured");
    }

    @Test
    void copilotNotSignedIn() {
        when(copilot.isAuthenticated()).thenReturn(false);
        ConnectionTestResult r = tester(Map.of(), null).test("copilot");
        assertThat(r.authenticated()).isFalse();
        assertThat(r.message()).contains("Not signed in");
    }

    @Test
    void unknownServiceRejected() {
        assertThatThrownBy(() -> tester(Map.of(), null).test("github"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
