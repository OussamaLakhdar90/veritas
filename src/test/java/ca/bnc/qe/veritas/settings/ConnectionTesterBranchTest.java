package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.snyk.SnykClient;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.secret.SecretProvider;
import ca.bnc.qe.veritas.vcs.GitHost;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage suite for {@link ConnectionTester}: every service branch (jira / bitbucket / confluence /
 * xray / copilot / unknown) crossed with every outcome branch (not-configured / success / unreachable /
 * auth-rejected / generic-failure) plus the "never leak the token" guarantee.
 */
class ConnectionTesterBranchTest {

    private static final String SECRET = "super-secret-token-value-DO-NOT-LEAK";

    private final JiraClient jira = mock(JiraClient.class);
    private final GitHost git = mock(GitHost.class);
    private final ConfluenceClient confluence = mock(ConfluenceClient.class);
    private final CopilotAuthService copilot = mock(CopilotAuthService.class);
    private final SnykClient snyk = mock(SnykClient.class);

    /** Build a tester with full control over each endpoint base URL and the secret map. */
    private ConnectionTester tester(Map<String, String> secretMap,
                                    String jiraBase, String bitbucketBase, String confluenceBase) {
        ConnectionsProperties props = new ConnectionsProperties();
        props.getJira().setBaseUrl(jiraBase);
        props.getBitbucket().setBaseUrl(bitbucketBase);
        props.getConfluence().setBaseUrl(confluenceBase);
        SecretProvider secrets = key -> Optional.ofNullable(secretMap.get(key));
        return new ConnectionTester(jira, git, confluence, copilot, snyk, props, secrets);
    }

    private static Map<String, String> secrets(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private void assertNeverLeaksToken(ConnectionTestResult r) {
        assertThat(r.message()).doesNotContain(SECRET);
    }

    // ---------------------------------------------------------------- input validation

    @Test
    void nullServiceIsRejected() {
        assertThatThrownBy(() -> tester(Map.of(), null, null, null).test(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void unknownServiceIsRejectedWithGuidance() {
        assertThatThrownBy(() -> tester(Map.of(), null, null, null).test("github"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown service")
                .hasMessageContaining("jira, bitbucket, confluence, xray, snyk, copilot");
    }

    @Test
    void serviceNameIsCaseInsensitive() {
        when(jira.whoAmI()).thenReturn("Mixed Case User");
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null)
                .test("JiRa");
        assertThat(r.service()).isEqualTo("jira");
        assertThat(r.authenticated()).isTrue();
    }

    // ---------------------------------------------------------------- JIRA

    @Test
    void jiraSuccess() {
        when(jira.whoAmI()).thenReturn("Alice QA");
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null)
                .test("jira");
        assertThat(r.service()).isEqualTo("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isTrue();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.message()).isEqualTo("Connected as Alice QA.");
        assertNeverLeaksToken(r);
    }

    @Test
    void jiraNotConfiguredWhenBaseUrlBlank() {
        // token present but base URL blank → first operand of && is false
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "   ", null, null).test("jira");
        assertThat(r.reachable()).isFalse();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isZero();
        assertThat(r.message()).contains("Not configured");
        verifyNoInteractions(jira);
    }

    @Test
    void jiraNotConfiguredWhenTokenMissing() {
        // base URL present but token absent → second operand of && is false
        ConnectionTestResult r = tester(secrets(), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("Not configured");
        verifyNoInteractions(jira);
    }

    @Test
    void jiraNotConfiguredWhenTokenBlank() {
        // token present-but-blank → present() maps to false
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", "   "), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("Not configured");
        verifyNoInteractions(jira);
    }

    @Test
    void jira401IsReachableButNotAuthenticated() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Jira /myself failed: 401 Unauthorized"));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.message()).contains("token was rejected");
        assertNeverLeaksToken(r);
    }

    @Test
    void jira403IsReachableButNotAuthenticated() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Jira /myself failed: 403 Forbidden"));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.message()).contains("token was rejected");
    }

    @Test
    void jiraUnauthorizedWordWithoutStatusDefaultsTo401() {
        // No HTTP status digits in the message, but the word "Unauthorized" appears → status defaults to 401.
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Unauthorized access to resource"));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.message()).contains("token was rejected");
    }

    @Test
    void jiraForbiddenWordWithoutStatusDefaultsTo401() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Forbidden by policy"));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void jiraGenericFailureWithHttpStatusInMessage() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Jira /myself failed: 500 Internal Server Error"));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.message()).contains("the request failed").contains("(HTTP 500)");
    }

    @Test
    void jiraGenericFailureWithoutStatusOrReachKeyword() {
        // No status digits, no reach keyword, no auth keyword → generic failure with status 0 and no "(HTTP ...)".
        when(jira.whoAmI()).thenThrow(new IllegalStateException("something weird happened"));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isZero();
        assertThat(r.message()).contains("the request failed").doesNotContain("(HTTP");
    }

    @Test
    void jiraNullExceptionMessageIsTreatedAsGenericFailure() {
        // e.getMessage() == null → msg becomes "" → no status, no keyword → generic failure path.
        when(jira.whoAmI()).thenThrow(new RuntimeException((String) null));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isZero();
        assertThat(r.message()).contains("the request failed").doesNotContain("(HTTP");
    }

    // ---------------------------------------------------------------- unreachable (each needle)

    @Test
    void unreachableTimedOut() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Read timed out"));
        assertUnreachable(tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira"));
    }

    @Test
    void unreachableConnection() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("Connection error to host"));
        assertUnreachable(tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira"));
    }

    @Test
    void unreachableUnknownHost() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("UnknownHostException: jira.bnc"));
        assertUnreachable(tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira"));
    }

    @Test
    void unreachableRefused() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("connect refused"));
        assertUnreachable(tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira"));
    }

    @Test
    void unreachableResolve() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("failed to resolve hostname"));
        assertUnreachable(tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira"));
    }

    @Test
    void unreachableIoError() {
        when(jira.whoAmI()).thenThrow(new IllegalStateException("I/O error on GET"));
        assertUnreachable(tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira"));
    }

    private void assertUnreachable(ConnectionTestResult r) {
        assertThat(r.reachable()).isFalse();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isZero();
        assertThat(r.message()).contains("Could not reach the host");
        assertNeverLeaksToken(r);
    }

    @Test
    void reachKeywordIsIgnoredWhenAnHttpStatusIsPresent() {
        // status != 0 means the unreachable branch is skipped even though a reach keyword is present.
        when(jira.whoAmI()).thenThrow(new IllegalStateException("502 Bad Gateway: Connection upstream lost"));
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("jira");
        assertThat(r.reachable()).isTrue();
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.message()).contains("the request failed").contains("(HTTP 502)");
    }

    // ---------------------------------------------------------------- BITBUCKET

    @Test
    void bitbucketSuccessUsesGitHost() {
        when(git.whoAmI()).thenReturn("git-bot");
        ConnectionTestResult r = tester(secrets("GIT_TOKEN", SECRET), null, "https://bitbucket.bnc", null)
                .test("bitbucket");
        assertThat(r.service()).isEqualTo("bitbucket");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isTrue();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.message()).contains("git-bot");
        verify(git).whoAmI();
        verifyNoInteractions(jira, confluence, copilot);
        assertNeverLeaksToken(r);
    }

    @Test
    void bitbucketNotConfiguredWhenBaseBlank() {
        ConnectionTestResult r = tester(secrets("GIT_TOKEN", SECRET), null, " ", null).test("bitbucket");
        assertThat(r.message()).contains("Not configured");
        verify(git, never()).whoAmI();
    }

    @Test
    void bitbucketAuthRejected() {
        when(git.whoAmI()).thenThrow(new IllegalStateException("Bitbucket failed: 401"));
        ConnectionTestResult r = tester(secrets("GIT_TOKEN", SECRET), null, "https://bitbucket.bnc", null)
                .test("bitbucket");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.message()).contains("token was rejected");
    }

    // ---------------------------------------------------------------- CONFLUENCE

    @Test
    void confluenceSuccessUsesConfluenceClient() {
        when(confluence.whoAmI()).thenReturn("Conf User");
        ConnectionTestResult r = tester(secrets("CONFLUENCE_API_TOKEN", SECRET), null, null, "https://conf.bnc")
                .test("confluence");
        assertThat(r.service()).isEqualTo("confluence");
        assertThat(r.authenticated()).isTrue();
        assertThat(r.message()).contains("Conf User");
        verify(confluence).whoAmI();
        verifyNoInteractions(jira, git, copilot);
    }

    @Test
    void confluenceNotConfiguredWhenTokenMissing() {
        ConnectionTestResult r = tester(secrets(), null, null, "https://conf.bnc").test("confluence");
        assertThat(r.message()).contains("Not configured");
        verify(confluence, never()).whoAmI();
    }

    @Test
    void confluenceUnreachable() {
        when(confluence.whoAmI()).thenThrow(new IllegalStateException("Connection timed out"));
        ConnectionTestResult r = tester(secrets("CONFLUENCE_API_TOKEN", SECRET), null, null, "https://conf.bnc")
                .test("confluence");
        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("Could not reach the host");
    }

    // ---------------------------------------------------------------- XRAY (reuses Jira host + token logic)

    @Test
    void xrayConfiguredViaXrayTokenOnly() {
        // first operand of the || is true (XRAY_API_TOKEN), second short-circuited
        when(jira.whoAmI()).thenReturn("Xray User");
        ConnectionTestResult r = tester(secrets("XRAY_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("xray");
        assertThat(r.service()).isEqualTo("xray");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isTrue();
        assertThat(r.message()).contains("Xray User");
        verify(jira).whoAmI();
    }

    @Test
    void xrayConfiguredViaJiraTokenFallback() {
        // first operand of the || is false (no XRAY token), second true (JIRA token)
        when(jira.whoAmI()).thenReturn("Jira Fallback");
        ConnectionTestResult r = tester(secrets("JIRA_API_TOKEN", SECRET), "https://jira.bnc", null, null).test("xray");
        assertThat(r.authenticated()).isTrue();
        assertThat(r.message()).contains("Jira Fallback");
    }

    @Test
    void xrayNotConfiguredWhenNeitherTokenPresent() {
        ConnectionTestResult r = tester(secrets(), "https://jira.bnc", null, null).test("xray");
        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("Not configured");
        verify(jira, never()).whoAmI();
    }

    @Test
    void xrayNotConfiguredWhenJiraBaseBlank() {
        // tokens present, but Jira base URL blank → not configured
        ConnectionTestResult r = tester(secrets("XRAY_API_TOKEN", SECRET, "JIRA_API_TOKEN", SECRET), "", null, null)
                .test("xray");
        assertThat(r.message()).contains("Not configured");
        verify(jira, never()).whoAmI();
    }

    // ---------------------------------------------------------------- COPILOT

    @Test
    void copilotNotSignedIn() {
        when(copilot.isAuthenticated()).thenReturn(false);
        ConnectionTestResult r = tester(secrets(), null, null, null).test("copilot");
        assertThat(r.service()).isEqualTo("copilot");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isZero();
        assertThat(r.message()).contains("Not signed in");
        verify(copilot, never()).getSessionToken();
    }

    @Test
    void copilotSignedInAndTokenExchangeOk() {
        when(copilot.isAuthenticated()).thenReturn(true);
        when(copilot.getSessionToken()).thenReturn(SECRET);
        ConnectionTestResult r = tester(secrets(), null, null, null).test("copilot");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isTrue();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.message()).contains("Copilot session token OK");
        verify(copilot).getSessionToken();
        assertNeverLeaksToken(r);
    }

    @Test
    void copilotSignedInButTokenExchangeFails() {
        when(copilot.isAuthenticated()).thenReturn(true);
        when(copilot.getSessionToken()).thenThrow(new IllegalStateException("token exchange failed: " + SECRET));
        ConnectionTestResult r = tester(secrets(), null, null, null).test("copilot");
        assertThat(r.reachable()).isTrue();
        assertThat(r.authenticated()).isFalse();
        assertThat(r.status()).isZero();
        assertThat(r.message()).contains("token exchange failed").contains("sign in again");
        // The probe must not echo the underlying exception (which here carries the secret).
        assertNeverLeaksToken(r);
    }
}
