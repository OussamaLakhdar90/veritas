package ca.bnc.qe.veritas.llm.copilot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.BillingMode;
import ca.bnc.qe.veritas.cost.CostEstimator;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.LiveModelMultipliers;
import ca.bnc.qe.veritas.cost.ModelCatalog;
import ca.bnc.qe.veritas.cost.ModelSpec;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.llm.CopilotHttpGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.retry.support.RetryTemplate;

/** Drives the HTTP Copilot gateway (device flow → session token → chat → models→cost) against WireMock. */
class CopilotHttpWireMockTest {

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

    private void writeStoredOAuth(CopilotProperties p) throws Exception {
        Files.writeString(Path.of(p.getTokenFile()),
                "{\"oauth_token\":{\"access_token\":\"oauth-1\",\"token_type\":\"bearer\",\"scope\":\"copilot\"}}");
    }

    @Test
    void deviceFlowPollsThenStoresOAuthToken() {
        wm.stubFor(post(urlPathEqualTo("/login/device/code")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"device_code\":\"dc-1\",\"user_code\":\"WX-YZ\","
                        + "\"verification_uri\":\"https://github.com/login/device\",\"interval\":0,\"expires_in\":900}")));
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
        CopilotAuthService auth = new CopilotAuthService(p, mapper, corp);
        var token = auth.deviceFlow(null);
        assertThat(token.accessToken()).isEqualTo("oauth-xyz");
        assertThat(Path.of(p.getTokenFile())).exists();
        assertThat(auth.isAuthenticated()).isTrue();
    }

    @Test
    void getSessionTokenExchangesStoredOAuth() throws Exception {
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"sess-1\",\"expires_at\":" + future + "}")));

        CopilotProperties p = props();
        writeStoredOAuth(p);
        CopilotAuthService auth = new CopilotAuthService(p, mapper, corp);
        assertThat(auth.getSessionToken()).isEqualTo("sess-1");
    }

    @Test
    void gatewayCompletesChatViaHttp() throws Exception {
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"sess-1\",\"expires_at\":" + future + "}")));
        wm.stubFor(post(urlPathEqualTo("/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello from copilot\"}}]}")));

        CopilotProperties p = props();
        writeStoredOAuth(p);
        CopilotAuthService auth = new CopilotAuthService(p, mapper, corp);
        CopilotHttpGateway gw = new CopilotHttpGateway(auth, p, mapper, corp);
        assertThat(gw.isAvailable()).isTrue();
        assertThat(gw.complete("hi", "claude-sonnet-4")).isEqualTo("hello from copilot");
    }

    @Test
    void modelsRefreshFeedsLiveMultiplierAndCost() throws Exception {
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"sess-1\",\"expires_at\":" + future + "}")));
        wm.stubFor(get(urlPathEqualTo("/models")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[{\"id\":\"claude-x\",\"billing\":{\"multiplier\":\"5\",\"is_premium\":true}}]}")));

        CopilotProperties p = props();
        writeStoredOAuth(p);
        CopilotAuthService auth = new CopilotAuthService(p, mapper, corp);
        LiveModelMultipliers live = new LiveModelMultipliers();
        CopilotModelsClient models = new CopilotModelsClient(auth, p, mapper, corp, live);

        assertThat(models.refresh()).isEqualTo(1);
        assertThat(live.multiplier("claude-x")).contains(5.0);
        assertThat(live.isPremium("claude-x")).isTrue();

        // The live 5× multiplier overrides the static 1× in the catalog → cost = 5 × pricePerRequest.
        ModelCatalog catalog = new ModelCatalog(BillingMode.PER_REQUEST, 0.04, 0.0, true,
                List.of(new ModelSpec("claude-x", ModelTier.STANDARD, 1.0, 0, 0, true)));
        CostResult cost = new CostEstimator(catalog, live).estimate("claude-x", "p", "r");
        assertThat(cost.premiumRequests()).isEqualTo(5.0);
        assertThat(cost.estCostUsd()).isEqualTo(0.2);
    }
}
