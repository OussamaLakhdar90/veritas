package ca.bnc.qe.veritas.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

class CorpHttpTest {

    private WireMockServer wm;
    private final Retries retries = new Retries(RetryTemplate.builder().maxAttempts(1).build());

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void getAndPostGoThroughRestClient() {
        wm.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        wm.stubFor(post(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"created\":1}")));

        CorpHttp corp = new CorpHttp(retries);
        String base = "http://localhost:" + wm.port();
        assertThat(corp.get(base + "/thing", Map.of("Accept", "application/json"))).contains("\"ok\":true");
        assertThat(corp.post(base + "/thing", Map.of("Authorization", "Bearer x"), "{}", "application/json"))
                .contains("\"created\":1");
    }

    @Test
    void insecureTlsDefaultsOffAndPlainCallsStillWork() {
        // Flag off (the default) → SimpleClientHttpRequestFactory path, unchanged behavior.
        wm.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        CorpHttp corp = new CorpHttp(retries, 10000, 60000, 180000, false,
                new org.springframework.mock.env.MockEnvironment());
        assertThat(corp.get("http://localhost:" + wm.port() + "/thing", Map.of())).contains("\"ok\":true");
    }

    @Test
    void insecureTlsOnConstructsAndServesPlainHttp() {
        // Flag on → JDK-HttpClient trust-all factory; must construct without throwing and still serve plain HTTP.
        wm.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        CorpHttp corp = new CorpHttp(retries, 10000, 60000, 180000, true,
                new org.springframework.mock.env.MockEnvironment());
        assertThat(corp.get("http://localhost:" + wm.port() + "/thing", Map.of())).contains("\"ok\":true");
    }

    @Test
    void insecureTlsRefusedOnServerProfile() {
        // Fail-closed: on the 'server' profile the flag is ignored (construction succeeds, verification stays on).
        wm.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        var env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("server");
        CorpHttp corp = new CorpHttp(retries, 10000, 60000, 180000, true, env);
        assertThat(corp.get("http://localhost:" + wm.port() + "/thing", Map.of())).contains("\"ok\":true");
    }

    @Test
    void powerShellScriptReadsSecretsFromEnvNotCommandLine() {
        String script = CorpHttp.buildPowerShellScript("POST", "https://jira.bnc/rest/api/2/issue",
                java.util.List.of("Authorization"), true, "application/json");
        assertThat(script).contains("Invoke-RestMethod");
        assertThat(script).contains("-Method POST");
        assertThat(script).contains("https://jira.bnc/rest/api/2/issue");
        // secrets are referenced from env, NOT interpolated into the command line
        assertThat(script).contains("'Authorization'=$env:VERITAS_HDR_0");
        assertThat(script).contains("-Body $env:VERITAS_BODY");
        assertThat(script).doesNotContain("Bearer");
        assertThat(script).contains("ConvertTo-Json");
    }
}
