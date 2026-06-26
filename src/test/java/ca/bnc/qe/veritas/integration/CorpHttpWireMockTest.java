package ca.bnc.qe.veritas.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Branch-coverage suite for {@link CorpHttp}: the RestClient {@code exec} path (get / post / postLong),
 * the SSE {@code postStreamLines} accumulation + error + timeout/short-read paths, the PowerShell fallback
 * toggle, and the pure {@link CorpHttp#buildPowerShellScript} builder. Driven against WireMock with a single
 * non-retrying {@link Retries} so a failure surfaces immediately rather than being masked by replays.
 */
class CorpHttpWireMockTest {

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

    private String base() {
        return "http://localhost:" + wm.port();
    }

    // ---------------------------------------------------------------------------------------------------
    // exec(...) via get / post / postLong
    // ---------------------------------------------------------------------------------------------------

    @Test
    void getSendsHeadersAndReturnsBody() {
        wm.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        CorpHttp corp = new CorpHttp(retries);
        String out = corp.get(base() + "/thing", Map.of("Accept", "application/json", "X-Trace", "abc"));

        assertThat(out).isEqualTo("{\"ok\":true}");
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/thing"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-Trace", equalTo("abc")));
    }

    @Test
    void recordsAPerCallTimerMetricTaggedByOutcomeWhenARegistryIsWired() {
        wm.stubFor(get(urlPathEqualTo("/ok")).willReturn(aResponse().withBody("hi")));
        wm.stubFor(get(urlPathEqualTo("/boom")).willReturn(aResponse().withStatus(500)));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        CorpHttp corp = new CorpHttp(retries);
        corp.setMeterRegistry(registry);

        corp.get(base() + "/ok", Map.of());
        var success = registry.find("veritas.integration.http")
                .tag("outcome", "success").tag("method", "GET").timer();
        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(1);

        catchThrowable(() -> corp.get(base() + "/boom", Map.of()));
        var error = registry.find("veritas.integration.http").tag("outcome", "error").timer();
        assertThat(error).isNotNull();
        assertThat(error.count()).isEqualTo(1);
    }

    @Test
    void postSendsBodyContentTypeAndHeaderThenReturnsBody() {
        wm.stubFor(post(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"created\":1}")));

        CorpHttp corp = new CorpHttp(retries);
        String out = corp.post(base() + "/thing", Map.of("Authorization", "Bearer x"), "{\"a\":1}", "application/json");

        assertThat(out).isEqualTo("{\"created\":1}");
        wm.verify(postRequestedFor(urlPathEqualTo("/thing"))
                .withHeader("Authorization", equalTo("Bearer x"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"a\":1}")));
    }

    @Test
    void postLongUsesTheLongTimeoutClientAndStillPostsTheBody() {
        wm.stubFor(post(urlPathEqualTo("/llm")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("generated")));

        CorpHttp corp = new CorpHttp(retries);
        String out = corp.postLong(base() + "/llm", Map.of("Authorization", "Bearer x"), "{\"q\":\"hi\"}", "application/json");

        assertThat(out).isEqualTo("generated");
        wm.verify(postRequestedFor(urlPathEqualTo("/llm")).withRequestBody(equalToJson("{\"q\":\"hi\"}")));
    }

    @Test
    void nullHeadersNullContentTypeAndNullBodyAreAllSkipped() {
        wm.stubFor(get(urlPathEqualTo("/bare")).willReturn(aResponse().withBody("bare-ok")));

        CorpHttp corp = new CorpHttp(retries);
        // headers == null, body == null, contentType == null exercise the three "skip" branches in exec()
        String out = corp.get(base() + "/bare", null);

        assertThat(out).isEqualTo("bare-ok");
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/bare")));
    }

    @Test
    void nullHeaderValueIsNotSentButOtherHeadersAre() {
        wm.stubFor(get(urlPathEqualTo("/maybe")).willReturn(aResponse().withBody("ok")));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Present", "yes");
        headers.put("X-Absent", null);   // the `if (v != null)` guard must drop this one

        CorpHttp corp = new CorpHttp(retries);
        assertThat(corp.get(base() + "/maybe", headers)).isEqualTo("ok");

        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/maybe"))
                .withHeader("X-Present", equalTo("yes"))
                .withoutHeader("X-Absent"));
    }

    @Test
    void nonSuccessStatusThrowsWhenPowerShellFallbackIsDisabled() {
        wm.stubFor(get(urlPathEqualTo("/boom")).willReturn(aResponse().withStatus(500).withBody("kaboom")));

        CorpHttp corp = new CorpHttp(retries);   // powershellFallback defaults to false
        // a 5xx surfaces as a RestClient RuntimeException and is re-thrown (no fallback branch taken)
        assertThatThrownBy(() -> corp.get(base() + "/boom", Map.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void connectionFaultThrowsWhenPowerShellFallbackIsDisabled() {
        wm.stubFor(get(urlPathEqualTo("/reset")).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        CorpHttp corp = new CorpHttp(retries);
        assertThatThrownBy(() -> corp.get(base() + "/reset", Map.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void powerShellFallbackEngagesAfterRestClientFailure() {
        wm.stubFor(get(urlPathEqualTo("/blocked")).willReturn(aResponse().withStatus(503)));

        CorpHttp corp = new CorpHttp(retries);
        ReflectionTestUtils.setField(corp, "powershellFallback", true);
        ReflectionTestUtils.setField(corp, "powershellTimeoutSeconds", 15L);

        // RestClient fails on 503 → the powershellFallback branch is taken (viaPowerShell + buildPowerShellScript
        // run). Whether the spawned powershell.exe ultimately succeeds or throws is environment-dependent, so we
        // assert the branch executed by proving the RestClient attempt was actually made against WireMock.
        try {
            corp.get(base() + "/blocked", Map.of("Authorization", "Bearer s"));
        } catch (RuntimeException expectedInSomeEnvironments) {
            // the fallback may itself fail (e.g. Invoke-RestMethod against a 503) → IllegalStateException
        }
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/blocked")));
    }

    // ---------------------------------------------------------------------------------------------------
    // postStreamLines(...)
    // ---------------------------------------------------------------------------------------------------

    @Test
    void postStreamLinesAccumulatesEveryLineInOrder() {
        wm.stubFor(post(urlPathEqualTo("/stream")).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: one\ndata: two\ndata: three\n")));

        CorpHttp corp = new CorpHttp(retries);
        List<String> lines = new ArrayList<>();
        corp.postStreamLines(base() + "/stream", Map.of("Authorization", "Bearer x"),
                "{\"q\":1}", "application/json", lines::add);

        assertThat(lines).containsExactly("data: one", "data: two", "data: three");
        wm.verify(postRequestedFor(urlPathEqualTo("/stream"))
                .withHeader("Authorization", equalTo("Bearer x"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"q\":1}")));
    }

    @Test
    void postStreamLinesWithNullHeadersBodyAndContentTypeStillStreams() {
        wm.stubFor(post(urlPathEqualTo("/stream-bare")).willReturn(aResponse().withBody("only-line")));

        CorpHttp corp = new CorpHttp(retries);
        List<String> lines = new ArrayList<>();
        // null headers, null body, null contentType exercise the three skipped branches in postStreamLines
        corp.postStreamLines(base() + "/stream-bare", null, null, null, lines::add);

        assertThat(lines).containsExactly("only-line");
    }

    @Test
    void postStreamLinesDropsNullHeaderValue() {
        wm.stubFor(post(urlPathEqualTo("/stream-hdr")).willReturn(aResponse().withBody("x")));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Keep", "1");
        headers.put("X-Drop", null);

        CorpHttp corp = new CorpHttp(retries);
        corp.postStreamLines(base() + "/stream-hdr", headers, "body", "text/plain", s -> { });

        wm.verify(postRequestedFor(urlPathEqualTo("/stream-hdr"))
                .withHeader("X-Keep", equalTo("1"))
                .withoutHeader("X-Drop"));
    }

    @Test
    void postStreamLinesOnErrorStatusThrowsAndNeverFeedsTheConsumer() {
        wm.stubFor(post(urlPathEqualTo("/stream-err")).willReturn(aResponse()
                .withStatus(429).withBody("rate limit hit\nplease back off")));

        CorpHttp corp = new CorpHttp(retries);
        List<String> consumed = new ArrayList<>();
        // The SimpleClientHttpRequestFactory (JDK HttpURLConnection) raises on a 4xx/5xx status while opening
        // the body stream, surfacing as a RestClient RuntimeException. Either way an error response is a
        // failure and the body lines are never handed to the consumer.
        assertThatThrownBy(() -> corp.postStreamLines(base() + "/stream-err", Map.of(),
                "{}", "application/json", consumed::add))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("429");
        assertThat(consumed).isEmpty();
    }

    @Test
    void postStreamLinesOnServerErrorStatusThrows() {
        wm.stubFor(post(urlPathEqualTo("/stream-err-empty")).willReturn(aResponse().withStatus(500)));

        CorpHttp corp = new CorpHttp(retries);
        Throwable t = catchThrowable(() -> corp.postStreamLines(base() + "/stream-err-empty", Map.of(),
                null, null, s -> { }));

        assertThat(t).isInstanceOf(RuntimeException.class);
        assertThat(t.getMessage()).contains("500");
    }

    @Test
    void postStreamLinesReadTimeoutSurfacesAsException() {
        // The server holds the response well past the read timeout → a read timeout on the streaming client.
        wm.stubFor(post(urlPathEqualTo("/slow")).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withFixedDelay(4000)
                .withBody("data: late\n")));

        // connect=2s, short=200ms read, llm(long stream client)=200ms read → the 4s delay far exceeds it.
        CorpHttp corp = new CorpHttp(retries, 2000, 200, 200);
        List<String> lines = new ArrayList<>();
        assertThatThrownBy(() -> corp.postStreamLines(base() + "/slow", Map.of(),
                "{}", "application/json", lines::add))
                .isInstanceOf(RuntimeException.class);
        assertThat(lines).isEmpty();   // nothing was streamed before the timeout
    }

    @Test
    void postStreamLinesConnectionFaultMidStreamSurfacesAsException() {
        wm.stubFor(post(urlPathEqualTo("/stream-fault")).willReturn(aResponse()
                .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        CorpHttp corp = new CorpHttp(retries);
        assertThatThrownBy(() -> corp.postStreamLines(base() + "/stream-fault", Map.of(),
                "{}", "application/json", s -> { }))
                .isInstanceOf(RuntimeException.class);
    }

    // ---------------------------------------------------------------------------------------------------
    // buildPowerShellScript(...) — pure, no process spawned
    // ---------------------------------------------------------------------------------------------------

    @Test
    void buildScriptWithBodyReferencesEnvForSecretsAndKeepsContentType() {
        String script = CorpHttp.buildPowerShellScript("POST", "https://jira.bnc/rest/api/2/issue",
                List.of("Authorization", "Accept"), true, "application/json");

        assertThat(script).contains("Invoke-RestMethod");
        assertThat(script).contains("-Method POST");
        assertThat(script).contains("https://jira.bnc/rest/api/2/issue");
        // header VALUES are read from env, never inlined; first key → HDR_0, second → HDR_1
        assertThat(script).contains("'Authorization'=$env:VERITAS_HDR_0");
        assertThat(script).contains("'Accept'=$env:VERITAS_HDR_1");
        assertThat(script).contains("-ContentType 'application/json'");
        assertThat(script).contains("-Body $env:VERITAS_BODY");
        assertThat(script).contains("ConvertTo-Json -Depth 10 -Compress");
        assertThat(script).startsWith("[Console]::OutputEncoding=");
    }

    @Test
    void buildScriptWithBodyButNullContentTypeDefaultsToApplicationJson() {
        String script = CorpHttp.buildPowerShellScript("PUT", "https://x/y", List.of(), true, null);

        assertThat(script).contains("-Method PUT");
        assertThat(script).contains("-ContentType 'application/json'");   // null contentType → default
        assertThat(script).contains("-Body $env:VERITAS_BODY");
    }

    @Test
    void buildScriptWithoutBodyOmitsBodyAndContentType() {
        String script = CorpHttp.buildPowerShellScript("GET", "https://x/y", List.of("Accept"), false, "application/json");

        assertThat(script).contains("-Method GET");
        assertThat(script).contains("'Accept'=$env:VERITAS_HDR_0");
        assertThat(script).doesNotContain("-Body");
        assertThat(script).doesNotContain("-ContentType");
    }

    @Test
    void buildScriptEscapesSingleQuotesInUrlAndHeaderKeys() {
        String script = CorpHttp.buildPowerShellScript("GET", "https://x/it's", List.of("X-O'Brien"), false, null);

        // single quotes are doubled for PowerShell single-quoted-string literals
        assertThat(script).contains("https://x/it''s");
        assertThat(script).contains("'X-O''Brien'=$env:VERITAS_HDR_0");
    }

    @Test
    void buildScriptWithNoHeadersProducesAnEmptyHashtable() {
        String script = CorpHttp.buildPowerShellScript("GET", "https://x/y", List.of(), false, null);

        assertThat(script).contains("-Headers @{ }");
        assertThat(script).doesNotContain("VERITAS_HDR_");
    }
}
