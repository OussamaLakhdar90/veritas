package ca.bnc.qe.veritas.llm.copilot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import ca.bnc.qe.veritas.cost.LiveModelMultipliers;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.integration.Retries;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.retry.support.RetryTemplate;

/**
 * Branch/edge coverage for {@link CopilotModelsClient#refresh()} — every {@code parseMultiplier} /
 * {@code isPremium} path (number, numeric string, bad string, missing/non-object billing, model_billing
 * fallback, is_premium flag, multiplier-implied premium), the {@code data}-wrapper vs bare-array shapes,
 * the id null/blank skip, the empty-response and null-response guards, and the error→IllegalStateException
 * path. Drives a real {@link CopilotAuthService} (stored OAuth → session-token exchange) against WireMock,
 * mirroring {@code CopilotHttpWireMockTest}.
 */
class CopilotModelsClientBranchTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Retries retries = new Retries(RetryTemplate.builder().maxAttempts(1).build());
    private final CorpHttp corp = new CorpHttp(retries);
    private LiveModelMultipliers live;

    @TempDir
    Path tmp;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        live = new LiveModelMultipliers();
        // Every test needs a valid session token from the exchange endpoint.
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        wm.stubFor(get(urlPathEqualTo("/copilot_internal/v2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"sess-1\",\"expires_at\":" + future + "}")));
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

    private CopilotModelsClient client(CopilotProperties p) throws Exception {
        Files.writeString(Path.of(p.getTokenFile()),
                "{\"oauth_token\":{\"access_token\":\"oauth-1\",\"token_type\":\"bearer\",\"scope\":\"copilot\"}}");
        CopilotAuthService auth = new CopilotAuthService(p, mapper, corp);
        return new CopilotModelsClient(auth, p, mapper, corp, live);
    }

    private void stubModels(String json) {
        wm.stubFor(get(urlPathEqualTo("/models")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(json)));
    }

    /** billing.multiplier as a JSON number → parsed as the numeric value; >0 ⇒ premium. */
    @Test
    void numericMultiplierIsParsedAndImpliesPremium() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-num\",\"billing\":{\"multiplier\":3}}]}");

        int n = client(p).refresh();

        assertThat(n).isEqualTo(1);
        assertThat(live.multiplier("m-num")).contains(3.0);
        assertThat(live.isPremium("m-num")).isTrue();   // multiplier 3 > 0 ⇒ premium even without is_premium
    }

    /** billing.multiplier as a numeric STRING with surrounding whitespace → trimmed + parsed. */
    @Test
    void numericStringMultiplierIsTrimmedAndParsed() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-str\",\"billing\":{\"multiplier\":\"  4.5 \"}}]}");

        assertThat(client(p).refresh()).isEqualTo(1);
        assertThat(live.multiplier("m-str")).contains(4.5);
        assertThat(live.isPremium("m-str")).isTrue();
    }

    /** A non-numeric multiplier string → NumberFormatException swallowed, no multiplier recorded, not premium. */
    @Test
    void nonNumericMultiplierStringYieldsNoMultiplierAndNotPremium() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-bad\",\"billing\":{\"multiplier\":\"free\"}}]}");

        assertThat(client(p).refresh()).isEqualTo(0);   // no multipliers parsed
        assertThat(live.multiplier("m-bad")).isEmpty();
        assertThat(live.isPremium("m-bad")).isFalse();
    }

    /** multiplier neither number nor textual (here a boolean) → parseMultiplier returns null. */
    @Test
    void nonNumberNonTextualMultiplierIsIgnored() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-bool\",\"billing\":{\"multiplier\":true}}]}");

        assertThat(client(p).refresh()).isEqualTo(0);
        assertThat(live.multiplier("m-bool")).isEmpty();
        assertThat(live.isPremium("m-bool")).isFalse();
    }

    /** billing absent and model_billing present → the fallback branch reads model_billing.multiplier. */
    @Test
    void modelBillingFallbackIsUsedWhenBillingAbsent() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-mb\",\"model_billing\":{\"multiplier\":\"2\"}}]}");

        assertThat(client(p).refresh()).isEqualTo(1);
        assertThat(live.multiplier("m-mb")).contains(2.0);
        assertThat(live.isPremium("m-mb")).isTrue();
    }

    /** billing present but NOT an object (a string) → parseMultiplier returns null; model not premium. */
    @Test
    void nonObjectBillingYieldsNoMultiplier() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-strbill\",\"billing\":\"nope\"}]}");

        assertThat(client(p).refresh()).isEqualTo(0);
        assertThat(live.multiplier("m-strbill")).isEmpty();
        assertThat(live.isPremium("m-strbill")).isFalse();
    }

    /** No billing/model_billing at all → null billing branch; not premium, no multiplier. */
    @Test
    void missingBillingEntirelyYieldsNoMultiplierAndNotPremium() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-none\"}]}");

        assertThat(client(p).refresh()).isEqualTo(0);
        assertThat(live.multiplier("m-none")).isEmpty();
        assertThat(live.isPremium("m-none")).isFalse();
    }

    /** is_premium=true with a zero multiplier → premium via the flag, even though multiplier 0 wouldn't imply it. */
    @Test
    void isPremiumFlagWinsEvenWithZeroMultiplier() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-flag\",\"billing\":{\"multiplier\":0,\"is_premium\":true}}]}");

        assertThat(client(p).refresh()).isEqualTo(1);
        assertThat(live.multiplier("m-flag")).contains(0.0);   // 0 is still a number → recorded
        assertThat(live.isPremium("m-flag")).isTrue();         // flag forces premium
    }

    /** Zero multiplier, no is_premium flag → recorded but NOT premium (m > 0.0 is false). */
    @Test
    void zeroMultiplierIsRecordedButNotPremium() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":[{\"id\":\"m-zero\",\"billing\":{\"multiplier\":0}}]}");

        assertThat(client(p).refresh()).isEqualTo(1);
        assertThat(live.multiplier("m-zero")).contains(0.0);
        assertThat(live.isPremium("m-zero")).isFalse();
    }

    /** Models with a null/blank id are skipped; only the valid one survives. */
    @Test
    void modelsWithMissingOrBlankIdAreSkipped() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":["
                + "{\"billing\":{\"multiplier\":2}},"                       // no id at all
                + "{\"id\":\"   \",\"billing\":{\"multiplier\":2}},"        // blank id
                + "{\"id\":\"keep\",\"billing\":{\"multiplier\":7}}]}");

        assertThat(client(p).refresh()).isEqualTo(1);
        assertThat(live.multiplier("keep")).contains(7.0);
        assertThat(live.size()).isEqualTo(1);
    }

    /** The response is a bare JSON array (no "data" wrapper) → root is iterated directly. */
    @Test
    void bareArrayWithoutDataWrapperIsIterated() throws Exception {
        CopilotProperties p = props();
        stubModels("[{\"id\":\"bare\",\"billing\":{\"multiplier\":\"6\",\"is_premium\":true}}]");

        assertThat(client(p).refresh()).isEqualTo(1);
        assertThat(live.multiplier("bare")).contains(6.0);
        assertThat(live.isPremium("bare")).isTrue();
    }

    /** An empty data array → zero models parsed; live multipliers cleared (size 0). */
    @Test
    void emptyDataArrayParsesZeroModels() throws Exception {
        CopilotProperties p = props();
        // Pre-seed live so we can prove update() actually cleared it on an empty refresh.
        live.update(java.util.Map.of("stale", 9.0), java.util.Set.of("stale"));
        stubModels("{\"data\":[]}");

        assertThat(client(p).refresh()).isEqualTo(0);
        assertThat(live.size()).isZero();
        assertThat(live.multiplier("stale")).isEmpty();
    }

    /** A 200 with an empty body → CorpHttp returns null → the {@code resp == null ? "{}" : resp} guard kicks in. */
    @Test
    void nullResponseBodyFallsBackToEmptyObject() throws Exception {
        CopilotProperties p = props();
        wm.stubFor(get(urlPathEqualTo("/models")).willReturn(aResponse().withStatus(200)));   // no body → null

        assertThat(client(p).refresh()).isEqualTo(0);
        assertThat(live.size()).isZero();
    }

    /** Multiple models in one payload → both multipliers land; premium set reflects each. */
    @Test
    void multipleModelsAreAllAggregated() throws Exception {
        CopilotProperties p = props();
        stubModels("{\"data\":["
                + "{\"id\":\"a\",\"billing\":{\"multiplier\":1}},"
                + "{\"id\":\"b\",\"billing\":{\"multiplier\":\"10\",\"is_premium\":true}}]}");

        assertThat(client(p).refresh()).isEqualTo(2);
        assertThat(live.multiplier("a")).contains(1.0);
        assertThat(live.multiplier("b")).contains(10.0);
        assertThat(live.isPremium("a")).isTrue();
        assertThat(live.isPremium("b")).isTrue();
    }

    /** A 500 from /models → CorpHttp throws → refresh propagates an (Illegal/Runtime) exception, not a swallow. */
    @Test
    void serverErrorPropagatesAsRuntimeException() throws Exception {
        CopilotProperties p = props();
        wm.stubFor(get(urlPathEqualTo("/models")).willReturn(aResponse().withStatus(500).withBody("boom")));

        CopilotModelsClient client = client(p);
        assertThatThrownBy(client::refresh).isInstanceOf(RuntimeException.class);
        // The error path must not corrupt live state.
        assertThat(live.size()).isZero();
    }

    /** No stored OAuth token → getSessionToken() throws before any /models call; refresh surfaces it. */
    @Test
    void notAuthenticatedThrowsBeforeFetching() {
        CopilotProperties p = props();
        // Deliberately do NOT write the token file.
        CopilotAuthService auth = new CopilotAuthService(p, mapper, corp);
        CopilotModelsClient client = new CopilotModelsClient(auth, p, mapper, corp, live);

        assertThatThrownBy(client::refresh)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not authenticated");
    }
}