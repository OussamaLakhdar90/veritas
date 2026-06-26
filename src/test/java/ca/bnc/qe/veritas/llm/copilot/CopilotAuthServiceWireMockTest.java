package ca.bnc.qe.veritas.llm.copilot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.DeviceCode;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.StoredOAuthToken;
import ca.bnc.qe.veritas.secret.SecretRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.retry.support.RetryTemplate;

/**
 * Branch-focused coverage of {@link CopilotAuthService}: the GitHub device flow (pending / slow_down /
 * expired / error / success / timeout), session-token exchange + caching + early refresh, the
 * not-authenticated path, {@link CopilotAuthService#isAuthenticated()}, {@link CopilotAuthService#verifyConnected()},
 * {@link CopilotAuthService#signOut()} and {@link CopilotAuthService#storedOAuth()} file parsing.
 */
class CopilotAuthServiceWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Retries retries = new Retries(RetryTemplate.builder().maxAttempts(1).build());
    private final CorpHttp corp = new CorpHttp(retries);

    @TempDir
    Path tmp;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private CopilotProperties props() {
        CopilotProperties p = new CopilotProperties();
        String base = "http://localhost:" + wm.port();
        p.setGithubBase(base);
        p.setApiBase(base);
        p.setCopilotBase(base);
        p.setTokenFile(tmp.resolve("copilot-auth.json").toString());
        p.setPollIntervalFloorMs(10);
        p.setMaxPollAttempts(5);
        return p;
    }

    private CopilotAuthService service(CopilotProperties p) {
        return new CopilotAuthService(p, mapper, corp);
    }

    private void writeStoredOAuth(CopilotProperties p) throws Exception {
        Files.writeString(Path.of(p.getTokenFile()),
                "{\"oauth_token\":{\"access_token\":\"oauth-1\",\"token_type\":\"bearer\",\"scope\":\"copilot\"}}");
    }

    private void stubDeviceCode() {
        wm.stubFor(post(urlPathEqualTo("/login/device/code")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"device_code\":\"dc-1\",\"user_code\":\"WX-YZ\","
                        + "\"verification_uri\":\"https://github.com/login/device\",\"interval\":0,\"expires_in\":900}")));
    }

    private void stubSessionToken(String token, long expiresAtEpoch) {
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"" + token + "\",\"expires_at\":" + expiresAtEpoch + "}")));
    }

    // ---------- deviceFlow ----------

    @Test
    void deviceFlowSurfacesDeviceCodeToPromptThenStoresOAuth() {
        stubDeviceCode();
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).inScenario("poll")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"authorization_pending\"}"))
                .willSetStateTo("ready"));
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).inScenario("poll")
                .whenScenarioStateIs("ready")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"oauth-xyz\",\"token_type\":\"bearer\",\"scope\":\"copilot\"}")));

        CopilotProperties p = props();
        CopilotAuthService auth = service(p);
        AtomicReference<DeviceCode> seen = new AtomicReference<>();
        StoredOAuthToken token = auth.deviceFlow(seen::set);

        assertThat(token.accessToken()).isEqualTo("oauth-xyz");
        assertThat(token.tokenType()).isEqualTo("bearer");
        assertThat(token.scope()).isEqualTo("copilot");
        // onPrompt received the parsed device-code handshake.
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().deviceCode()).isEqualTo("dc-1");
        assertThat(seen.get().userCode()).isEqualTo("WX-YZ");
        assertThat(seen.get().verificationUri()).isEqualTo("https://github.com/login/device");
        assertThat(seen.get().expiresIn()).isEqualTo(900);
        // OAuth was persisted in the nested {"oauth_token":{...}} shape and is now readable.
        assertThat(Path.of(p.getTokenFile())).exists();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(SecretRegistry.snapshot()).contains("oauth-xyz");
    }

    @Test
    void deviceFlowToleratesNullPromptAndNullCodeResponseUsingDefaults() {
        // Null body from the device-code endpoint → service falls back to "{}" and zero/default fields.
        wm.stubFor(post(urlPathEqualTo("/login/device/code")).willReturn(aResponse().withStatus(204)));
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"oauth-null\",\"token_type\":\"bearer\",\"scope\":\"copilot\"}")));

        CopilotProperties p = props();
        StoredOAuthToken token = service(p).deviceFlow(null);
        assertThat(token.accessToken()).isEqualTo("oauth-null");
    }

    @Test
    void deviceFlowBacksOffOnSlowDownThenSucceeds() {
        stubDeviceCode();
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).inScenario("slow")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"slow_down\"}"))
                .willSetStateTo("go"));
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).inScenario("slow")
                .whenScenarioStateIs("go")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"oauth-after-slow\"}")));

        StoredOAuthToken token = service(props()).deviceFlow(null);
        // token_type / scope absent → defaults kick in.
        assertThat(token.accessToken()).isEqualTo("oauth-after-slow");
        assertThat(token.tokenType()).isEqualTo("bearer");
        assertThat(token.scope()).isEqualTo("copilot");
    }

    @Test
    void deviceFlowThrowsWhenDeviceCodeExpired() {
        stubDeviceCode();
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"expired_token\"}")));

        CopilotProperties p = props();
        assertThatThrownBy(() -> service(p).deviceFlow(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Device code expired");
        assertThat(Path.of(p.getTokenFile())).doesNotExist();
    }

    @Test
    void deviceFlowThrowsOnGenericOAuthError() {
        stubDeviceCode();
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"access_denied\"}")));

        assertThatThrownBy(() -> service(props()).deviceFlow(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OAuth error: access_denied");
    }

    @Test
    void deviceFlowTimesOutWhenAlwaysPendingOrEmpty() {
        stubDeviceCode();
        // Always-empty body → parsed as "{}" → no error, no access_token → loop exhausts maxPollAttempts.
        wm.stubFor(post(urlPathEqualTo("/login/oauth/access_token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("")));

        CopilotProperties p = props();
        p.setMaxPollAttempts(2);
        assertThatThrownBy(() -> service(p).deviceFlow(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out after 2 attempts");
    }

    // ---------- getSessionToken ----------

    @Test
    void getSessionTokenExchangesStoredOAuthAndCaches() throws Exception {
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        stubSessionToken("sess-1", future);

        CopilotProperties p = props();
        writeStoredOAuth(p);
        CopilotAuthService auth = service(p);

        assertThat(auth.getSessionToken()).isEqualTo("sess-1");
        assertThat(SecretRegistry.snapshot()).contains("sess-1");

        // Second call is served from cache (not near expiry) → no second HTTP exchange even if the stub is removed.
        wm.resetAll();
        assertThat(auth.getSessionToken()).isEqualTo("sess-1");
    }

    @Test
    void getSessionTokenRefreshesWhenCachedTokenIsNearExpiry() throws Exception {
        // First exchange returns a token that expires within the 5-minute refresh window (now+60s) → not cacheable.
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).inScenario("refresh")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"sess-near\",\"expires_at\":" + Instant.now().plusSeconds(60).getEpochSecond() + "}"))
                .willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).inScenario("refresh")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"sess-fresh\",\"expires_at\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}")));

        CopilotProperties p = props();
        writeStoredOAuth(p);
        CopilotAuthService auth = service(p);

        assertThat(auth.getSessionToken()).isEqualTo("sess-near");
        // Cached token is inside the refresh window → service re-exchanges and returns the fresh one.
        assertThat(auth.getSessionToken()).isEqualTo("sess-fresh");
    }

    @Test
    void getSessionTokenDefaultsExpiryWhenServerOmitsExpiresAt() throws Exception {
        // No expires_at → service defaults to now+25min, which is outside the 5-min refresh window → cacheable.
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"sess-noexp\"}")));

        CopilotProperties p = props();
        writeStoredOAuth(p);
        CopilotAuthService auth = service(p);

        assertThat(auth.getSessionToken()).isEqualTo("sess-noexp");
        wm.resetAll();
        // Served from cache on the next call (25-min default expiry keeps it valid).
        assertThat(auth.getSessionToken()).isEqualTo("sess-noexp");
    }

    @Test
    void getSessionTokenThrowsWhenNotAuthenticated() {
        CopilotProperties p = props();   // no token file written
        assertThatThrownBy(() -> service(p).getSessionToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    void getSessionTokenThrowsWhenExchangeReturnsNoToken() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"bad credentials\"}")));

        CopilotProperties p = props();
        writeStoredOAuth(p);
        assertThatThrownBy(() -> service(p).getSessionToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token exchange failed");
    }

    @Test
    void getSessionTokenPropagatesHttpFailureAsRuntimeException() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token"))
                .willReturn(aResponse().withStatus(500)));

        CopilotProperties p = props();
        writeStoredOAuth(p);
        // The RestClient 5xx surfaces as a RuntimeException, re-thrown unchanged by getSessionToken.
        assertThatThrownBy(() -> service(p).getSessionToken())
                .isInstanceOf(RuntimeException.class);
    }

    // ---------- isAuthenticated / verifyConnected ----------

    @Test
    void isAuthenticatedReflectsTokenFilePresence() throws Exception {
        CopilotProperties p = props();
        assertThat(service(p).isAuthenticated()).isFalse();
        writeStoredOAuth(p);
        assertThat(service(p).isAuthenticated()).isTrue();
    }

    @Test
    void verifyConnectedFalseWhenNoStoredOAuth() {
        CopilotProperties p = props();   // no token file
        assertThat(service(p).verifyConnected()).isFalse();
    }

    @Test
    void verifyConnectedTrueWhenSessionExchangeSucceeds() throws Exception {
        stubSessionToken("sess-ok", Instant.now().plusSeconds(3600).getEpochSecond());
        CopilotProperties p = props();
        writeStoredOAuth(p);
        assertThat(service(p).verifyConnected()).isTrue();
    }

    @Test
    void verifyConnectedFalseWhenStoredOAuthIsStaleAndExchangeFails() throws Exception {
        // Token file exists but the exchange rejects it → not actually connected.
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token"))
                .willReturn(aResponse().withStatus(401)));
        CopilotProperties p = props();
        writeStoredOAuth(p);
        assertThat(service(p).verifyConnected()).isFalse();
    }

    // ---------- signOut ----------

    @Test
    void signOutDeletesTokenFileAndClearsCache() throws Exception {
        stubSessionToken("sess-1", Instant.now().plusSeconds(3600).getEpochSecond());
        CopilotProperties p = props();
        writeStoredOAuth(p);
        CopilotAuthService auth = service(p);

        // Warm the cache, then sign out.
        assertThat(auth.getSessionToken()).isEqualTo("sess-1");
        auth.signOut();

        assertThat(Path.of(p.getTokenFile())).doesNotExist();
        assertThat(auth.isAuthenticated()).isFalse();
        // Cache cleared + file gone → getSessionToken now reports not-authenticated.
        assertThatThrownBy(auth::getSessionToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not authenticated");
    }

    @Test
    void signOutOnMissingFileIsHarmless() {
        CopilotProperties p = props();   // file never created
        CopilotAuthService auth = service(p);
        auth.signOut();   // deleteIfExists swallows the absence
        assertThat(auth.isAuthenticated()).isFalse();
    }

    // ---------- storedOAuth parsing ----------

    @Test
    void storedOAuthReadsFlatTokenShape() throws Exception {
        CopilotProperties p = props();
        // No "oauth_token" wrapper → service reads the root node directly.
        Files.writeString(Path.of(p.getTokenFile()),
                "{\"access_token\":\"flat-tok\",\"token_type\":\"pat\",\"scope\":\"read\"}");

        StoredOAuthToken t = service(p).storedOAuth();
        assertThat(t).isNotNull();
        assertThat(t.accessToken()).isEqualTo("flat-tok");
        assertThat(t.tokenType()).isEqualTo("pat");
        assertThat(t.scope()).isEqualTo("read");
        assertThat(SecretRegistry.snapshot()).contains("flat-tok");
    }

    @Test
    void storedOAuthReturnsNullWhenAccessTokenMissing() throws Exception {
        CopilotProperties p = props();
        Files.writeString(Path.of(p.getTokenFile()), "{\"oauth_token\":{\"token_type\":\"bearer\"}}");
        assertThat(service(p).storedOAuth()).isNull();
        assertThat(service(p).isAuthenticated()).isFalse();
    }

    @Test
    void storedOAuthReturnsNullOnMalformedJson() throws Exception {
        CopilotProperties p = props();
        Files.writeString(Path.of(p.getTokenFile()), "not-json-at-all{");
        assertThat(service(p).storedOAuth()).isNull();
    }

    @Test
    void storedOAuthReturnsNullWhenFileAbsent() {
        assertThat(service(props()).storedOAuth()).isNull();
    }
}
