package ca.bnc.qe.veritas.integration.snyk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

/** Verifies the Snyk client against WireMock: token auth, REST orgs (+pagination), and v1 aggregated-issues parsing. */
class SnykRestClientWireMockTest {

    private WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretProvider secrets = key -> Optional.of("token-abc");
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

    private SnykRestClient client() {
        ConnectionsProperties p = new ConnectionsProperties();
        p.getSnyk().setBaseUrl("http://localhost:" + wm.port());
        return new SnykRestClient(p, secrets, mapper, new CorpHttp(retries), "2024-10-15");
    }

    @Test
    void whoAmiParsesNameAndSendsTokenAuth() {
        wm.stubFor(get(urlPathEqualTo("/rest/self")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":{\"id\":\"u1\",\"attributes\":{\"name\":\"Oussama Lakhdar\"}}}")));
        assertThat(client().whoAmI()).isEqualTo("Oussama Lakhdar");
        wm.verify(getRequestedFor(urlPathEqualTo("/rest/self"))
                .withHeader("Authorization", equalTo("token token-abc")));
    }

    @Test
    void listOrgsFollowsPaginationLinks() {
        wm.stubFor(get(urlPathEqualTo("/rest/orgs")).inScenario("orgs")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"id\":\"org-7576\",\"attributes\":{\"slug\":\"app7576\","
                                + "\"name\":\"CIAM Profile\"}}],\"links\":{\"next\":\"/rest/orgs?starting_after=x\"}}"))
                .willSetStateTo("page2"));
        wm.stubFor(get(urlPathEqualTo("/rest/orgs")).inScenario("orgs")
                .whenScenarioStateIs("page2")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"id\":\"org-7571\",\"attributes\":{\"slug\":\"app7571\","
                                + "\"name\":\"CIAM Access\"}}],\"links\":{}}")));
        List<SnykOrg> orgs = client().listOrgs();
        assertThat(orgs).extracting(SnykOrg::slug).containsExactly("app7576", "app7571");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/rest/orgs")));
    }

    @Test
    void aggregatedIssuesParsesSeverityPackageAndFixInfo() {
        wm.stubFor(post(urlPathEqualTo("/v1/org/org-1/project/proj-1/aggregated-issues"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"issues\":["
                                + "{\"id\":\"i1\",\"pkgName\":\"com.fasterxml.jackson.core:jackson-databind\","
                                + "\"pkgVersions\":[\"3.1.1\"],\"priorityScore\":298,"
                                + "\"issueData\":{\"id\":\"SNYK-1\",\"severity\":\"critical\",\"title\":\"Deserialization\","
                                + "\"identifiers\":{\"CVE\":[\"CVE-2020-1\"],\"CWE\":[\"CWE-502\"]},\"cvssScore\":9.2},"
                                + "\"fixInfo\":{\"isFixable\":false,\"fixedIn\":[]}},"
                                + "{\"id\":\"i2\",\"pkgName\":\"org.apache.commons:commons-lang3\","
                                + "\"pkgVersions\":[\"3.12.0\"],\"priorityScore\":182,"
                                + "\"issueData\":{\"id\":\"SNYK-2\",\"severity\":\"high\",\"title\":\"Recursion\","
                                + "\"identifiers\":{\"CVE\":[\"CVE-2024-2\"],\"CWE\":[\"CWE-674\"]},\"cvssScore\":7.5},"
                                + "\"fixInfo\":{\"isFixable\":true,\"fixedIn\":[\"3.18.0\"]}}]}")));
        List<SnykIssue> issues = client().aggregatedIssues("org-1", "proj-1");
        assertThat(issues).hasSize(2);
        SnykIssue critical = issues.get(0);
        assertThat(critical.severity()).isEqualTo("critical");
        assertThat(critical.pkgName()).contains("jackson-databind");
        assertThat(critical.pkgVersion()).isEqualTo("3.1.1");
        assertThat(critical.cve()).isEqualTo("CVE-2020-1");
        assertThat(critical.cvss()).isEqualTo(9.2);
        assertThat(critical.riskScore()).isEqualTo(298);
        assertThat(critical.fixable()).isFalse();
        assertThat(critical.safeVersion()).isNull();
        SnykIssue high = issues.get(1);
        assertThat(high.fixable()).isTrue();
        assertThat(high.safeVersion()).isEqualTo("3.18.0");
    }
}
