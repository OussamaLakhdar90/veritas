package ca.bnc.qe.veritas.contract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Branch/edge coverage for {@link SpecResolver} complementing {@link SpecResolverTest}: the absolute-path branch,
 * auto-discovery depth-walk + the multi-candidate listing error, every {@code looksLikeSpec} acceptance/rejection
 * route, the directory-exclusion filters, the dotless and leading-dot {@code stem} cases, the CONFLUENCE id route,
 * and the full LIVE_DOCS HTTP success/empty-body/null-body/transport-failure/server-error set driven against a
 * WireMock endpoint.
 */
class SpecResolverBranchTest {

    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private SpecResolver resolver(ConfluenceClient confluence) {
        return new SpecResolver(confluence);
    }

    private SpecResolver resolver() {
        return new SpecResolver(id -> null);
    }

    // ---------------------------------------------------------------- REPO_PATH

    @Test
    void readsSpecFromAbsolutePathIgnoringRepoRoot(@TempDir Path repo, @TempDir Path elsewhere) throws Exception {
        // ref is absolute -> p.isAbsolute() == true -> repoPath.resolve is bypassed entirely.
        Path abs = elsewhere.resolve("contract.yaml");
        Files.writeString(abs, "openapi: 3.0.2\npaths: {}\n");

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, abs.toString()), repo);

        assertThat(spec.id()).isEqualTo("contract");
        assertThat(spec.content()).contains("openapi: 3.0.2");
    }

    @Test
    void autoDiscoversSpecNestedDeepInTheClone(@TempDir Path repo) throws Exception {
        // Lives 4 dirs deep -- well within the depth-6 walk; only one match -> it is used.
        Path nested = repo.resolve("a/b/c/d");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("the-openapi.yml"), "openapi: 3.1.0\npaths: {}\n");

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "missing.yaml"), repo);

        assertThat(spec.id()).isEqualTo("the-openapi");
        assertThat(spec.content()).contains("openapi: 3.1.0");
    }

    @Test
    void listsCandidatesWhenSeveralSpecsMatchAndNoneIsTheGivenPath(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("openapi-v1.yaml"), "openapi: 3.0.0\npaths: {}\n");
        Files.writeString(repo.resolve("swagger.json"), "{\"swagger\":\"2.0\",\"paths\":{}}");

        assertThatThrownBy(() -> resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "does-not-exist.yaml"), repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Spec 'does-not-exist.yaml' not found")
                .hasMessageContaining("Candidate spec files in this repo")
                .hasMessageContaining("openapi-v1.yaml")
                .hasMessageContaining("swagger.json")
                .hasMessageContaining("set the Spec path to one of these");
    }

    @Test
    void detectsSpecByApiDotNamePrefix(@TempDir Path repo) throws Exception {
        // name starts with "api." -> accepted by name without sniffing the head.
        Files.writeString(repo.resolve("api.contract.yaml"), "just: some\nyaml: here\n");

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "absent.yaml"), repo);

        assertThat(spec.id()).isEqualTo("api.contract");
        assertThat(spec.content()).contains("just: some");
    }

    @Test
    void detectsSpecByJsonOpenapiHeadWhenNameIsNeutral(@TempDir Path repo) throws Exception {
        // Neutral file name (no openapi/swagger/api. token) but the JSON head declares "openapi" -> accepted via sniff.
        Files.writeString(repo.resolve("contract.json"), "{\n  \"openapi\": \"3.0.0\",\n  \"paths\": {}\n}");

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "absent.json"), repo);

        assertThat(spec.id()).isEqualTo("contract");
        assertThat(spec.content()).contains("\"openapi\"");
    }

    @Test
    void detectsSpecBySwaggerYamlHeadWhenNameIsNeutral(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("contract.yaml"), "swagger: \"2.0\"\npaths:\n  /x: {}\n");

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "absent.yaml"), repo);

        assertThat(spec.content()).startsWith("swagger:");
    }

    @Test
    void ignoresYamlWithoutAnyOpenApiOrSwaggerMarker(@TempDir Path repo) throws Exception {
        // Right extension, neutral name, head has no openapi/swagger token -> rejected -> "no spec found".
        Files.writeString(repo.resolve("config.yaml"), "server:\n  port: 8080\nlogging: debug\n");

        assertThatThrownBy(() -> resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "absent.yaml"), repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No OpenAPI/Swagger spec found");
    }

    @Test
    void ignoresFilesWithNonSpecExtensions(@TempDir Path repo) throws Exception {
        // .txt / .md never qualify even when the body screams openapi -- extension gate rejects first.
        Files.writeString(repo.resolve("openapi.txt"), "openapi: 3.0.0\npaths: {}\n");
        Files.writeString(repo.resolve("swagger.md"), "swagger: \"2.0\"\n");

        assertThatThrownBy(() -> resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "absent.yaml"), repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No OpenAPI/Swagger spec found");
    }

    @Test
    void skipsSpecsInsideExcludedDirectories(@TempDir Path repo) throws Exception {
        // The "/target/", "/node_modules/", "/build/" guards are slash-wrapped (they match a *nested* segment),
        // while ".git/" is matched via startsWith on the relativized path. Place each excluded copy where its guard
        // fires: nested under module/ for the slash-wrapped ones, and at the root for .git.
        Path module = repo.resolve("module");
        for (String dir : new String[] {"target", "node_modules", "build"}) {
            Path d = module.resolve(dir);
            Files.createDirectories(d);
            Files.writeString(d.resolve("openapi.yaml"), "openapi: 3.0.0\npaths: {}\n");
        }
        Path git = repo.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("openapi.yaml"), "openapi: 3.0.0\npaths: {}\n");

        Files.writeString(repo.resolve("real-openapi.yaml"), "openapi: 3.0.9\npaths: {}\n");

        // Exactly one candidate survives the exclusion filter -> it is the one used.
        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "absent.yaml"), repo);

        assertThat(spec.id()).isEqualTo("real-openapi");
        assertThat(spec.content()).contains("openapi: 3.0.9");
    }

    @Test
    void aDirectoryNamedLikeASpecIsNotTreatedAsARegularFile(@TempDir Path repo) throws Exception {
        // Files.isRegularFile(dir) is false -> the ref does not short-circuit to read(); with no other candidate
        // present, discovery yields nothing and the "no spec found" path is taken (the directory is never read).
        Files.createDirectory(repo.resolve("openapi.yaml"));   // a *directory* literally named openapi.yaml

        assertThatThrownBy(() -> resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, "openapi.yaml"), repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No OpenAPI/Swagger spec found");
    }

    @Test
    void stemOfDotlessFileIsTheWholeName(@TempDir Path repo) throws Exception {
        // A file literally named "openapi" (no extension) is referenced by an absolute path and read directly;
        // lastIndexOf('.') == -1 so stem() returns the whole name.
        Path f = repo.resolve("openapi");
        Files.writeString(f, "openapi: 3.0.0\npaths: {}\n");

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, f.toAbsolutePath().toString()), repo);

        assertThat(spec.id()).isEqualTo("openapi");
    }

    @Test
    void stemOfDotfileWithNoExtensionKeepsLeadingDot(@TempDir Path repo) throws Exception {
        // Name ".openapi" -> lastIndexOf('.') == 0, which is NOT > 0, so stem() returns the whole ".openapi".
        Path f = repo.resolve(".openapi");
        Files.writeString(f, "openapi: 3.0.0\npaths: {}\n");

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.REPO_PATH, f.toAbsolutePath().toString()), repo);

        assertThat(spec.id()).isEqualTo(".openapi");
    }

    // ---------------------------------------------------------------- LIVE_DOCS

    @Test
    void fetchesLiveApiDocsBodyOverHttp() {
        wm.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"openapi\":\"3.0.0\",\"paths\":{}}")));

        SpecInput spec = resolver().resolve(
                new SpecSource(SpecSourceKind.LIVE_DOCS, "http://localhost:" + wm.port() + "/v3/api-docs"), null);

        assertThat(spec.id()).isEqualTo("live-api-docs");
        assertThat(spec.content()).contains("\"openapi\":\"3.0.0\"");
    }

    @Test
    void rejectsEmptyLiveApiDocsBody() {
        wm.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(200).withBody("   ")));

        String url = "http://localhost:" + wm.port() + "/v3/api-docs";
        assertThatThrownBy(() -> resolver().resolve(new SpecSource(SpecSourceKind.LIVE_DOCS, url), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned an empty body")
                .hasMessageContaining(url);
    }

    @Test
    void rejectsNullLiveApiDocsBodyFrom204() {
        // 204 No Content -> RestClient yields a null String body -> the null branch of the guard.
        wm.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(204)));

        String url = "http://localhost:" + wm.port() + "/v3/api-docs";
        assertThatThrownBy(() -> resolver().resolve(new SpecSource(SpecSourceKind.LIVE_DOCS, url), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned an empty body");
    }

    @Test
    void wrapsTransportFailureFetchingLiveApiDocs() {
        // Point at a closed port: the connection is refused -> RestClientException -> wrapped IllegalStateException.
        int closed = wm.port();
        wm.stop();   // free the port so the connection is refused
        String url = "http://localhost:" + closed + "/v3/api-docs";

        assertThatThrownBy(() -> resolver().resolve(new SpecSource(SpecSourceKind.LIVE_DOCS, url), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to fetch live API docs from")
                .hasMessageContaining(url);
    }

    @Test
    void wrapsServerErrorFetchingLiveApiDocs() {
        wm.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        String url = "http://localhost:" + wm.port() + "/v3/api-docs";
        assertThatThrownBy(() -> resolver().resolve(new SpecSource(SpecSourceKind.LIVE_DOCS, url), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to fetch live API docs from");
    }

    // ---------------------------------------------------------------- CONFLUENCE

    @Test
    void resolvesConfluencePageIdIntoSpecInput() {
        String storage = "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body>"
                + "<![CDATA[openapi: 3.0.0\npaths:\n  /p: {}\n]]></ac:plain-text-body></ac:structured-macro>";
        ConfluenceClient confluence = id -> new ConfluencePage(id, "My API", storage);

        SpecInput spec = resolver(confluence)
                .resolve(new SpecSource(SpecSourceKind.CONFLUENCE, "777"), null);

        assertThat(spec.id()).isEqualTo("confluence-777");
        assertThat(spec.content()).startsWith("openapi: 3.0.0");
    }
}