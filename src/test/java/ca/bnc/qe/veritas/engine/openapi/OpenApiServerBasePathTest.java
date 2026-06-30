package ca.bnc.qe.veritas.engine.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the dogfood-found gap: a spec path is relative to {@code servers[].url}, so the extractor
 * must prepend that base path (e.g. {@code servers:/api} + {@code /widget} → {@code /api/widget}). Without it, every
 * endpoint of a spec that declares a base path read as MISSING/EXTRA against code mapped under the same prefix.
 */
class OpenApiServerBasePathTest {

    private final OpenApiModelExtractor extractor = new OpenApiModelExtractor();

    private List<Endpoint> endpointsOf(String yaml) {
        return extractor.extract("spec", yaml).model().endpoints();
    }

    private static String widgetSpec(String serversBlock) {
        return "openapi: 3.0.1\n"
                + "info: { title: t, version: '1.0' }\n"
                + serversBlock
                + "paths:\n"
                + "  /widget:\n"
                + "    get:\n"
                + "      responses: { '200': { description: ok } }\n";
    }

    @Test
    void prependsAnAbsoluteServerUrlBasePath() {
        List<Endpoint> eps = endpointsOf(widgetSpec("servers:\n  - url: http://localhost:8080/api\n"));
        assertThat(eps).extracting(Endpoint::pathTemplate).containsExactly("/api/widget");
    }

    @Test
    void prependsARelativeServerBasePath() {
        List<Endpoint> eps = endpointsOf(widgetSpec("servers:\n  - url: /api/v2\n"));
        assertThat(eps).extracting(Endpoint::pathTemplate).containsExactly("/api/v2/widget");
    }

    @Test
    void leavesPathsUnchangedWhenThereIsNoServerOrJustRoot() {
        assertThat(endpointsOf(widgetSpec("")).get(0).pathTemplate()).isEqualTo("/widget");
        assertThat(endpointsOf(widgetSpec("servers:\n  - url: http://localhost:8080/\n")).get(0).pathTemplate())
                .isEqualTo("/widget");
    }

    @Test
    void salvagesLiteralPathFromATemplatedHost() {
        // Only the host is templated; the path /api is literal, so it must still prefix (keeps code/spec symmetric).
        assertThat(endpointsOf(widgetSpec("servers:\n  - url: 'https://{host}/api'\n")).get(0).pathTemplate())
                .isEqualTo("/api/widget");
    }

    @Test
    void ignoresAServerWhosePathItselfIsTemplated() {
        // The path segment is templated — no static base to recover, so leave the path as-is rather than guess.
        assertThat(endpointsOf(widgetSpec("servers:\n  - url: 'https://api.bnc.ca/{basePath}'\n")).get(0).pathTemplate())
                .isEqualTo("/widget");
    }

    @Test
    void serverBasePathHelperHandlesTheEdgeCases() {
        assertThat(OpenApiModelExtractor.serverBasePath(server("http://h:8080/api"))).isEqualTo("/api");
        assertThat(OpenApiModelExtractor.serverBasePath(server("/api/"))).isEqualTo("/api");   // trailing slash stripped
        assertThat(OpenApiModelExtractor.serverBasePath(server("api"))).isEqualTo("/api");      // leading slash added
        assertThat(OpenApiModelExtractor.serverBasePath(server("http://h:8080"))).isEmpty();    // no path
        assertThat(OpenApiModelExtractor.serverBasePath(new OpenAPI())).isEmpty();              // no servers
    }

    @Test
    void serverBasePathSalvagesLiteralPathsFromTemplatedServers() {
        // Templated scheme/host/port but a literal path → keep the path.
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://{env}.bnc.ca/ciam"))).isEqualTo("/ciam");
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://{host}:{port}/ciam"))).isEqualTo("/ciam");
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://{host}/api?x=1#f"))).isEqualTo("/api");
        // Templated path segment, fully-templated server, or no path → no recoverable base.
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://api.bnc.ca/{basePath}"))).isEmpty();
        assertThat(OpenApiModelExtractor.serverBasePath(server("/{version}"))).isEmpty();
        assertThat(OpenApiModelExtractor.serverBasePath(server("{server}"))).isEmpty();
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://{host}.bnc.ca"))).isEmpty();      // no path
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://{host}.bnc.ca/"))).isEmpty();     // root only
    }

    @Test
    void multipleServersSharingOneBaseUseIt() {
        OpenAPI api = new OpenAPI()
                .addServersItem(new Server().url("https://a.bnc.ca/ciam"))
                .addServersItem(new Server().url("https://b.bnc.ca/ciam"));
        assertThat(OpenApiModelExtractor.serverBasePath(api)).isEqualTo("/ciam");
    }

    @Test
    void conflictingServerBasesApplyNoPrefix() {
        OpenAPI api = new OpenAPI()
                .addServersItem(new Server().url("https://a.bnc.ca/api/v1"))
                .addServersItem(new Server().url("https://a.bnc.ca/api/v2"));
        assertThat(OpenApiModelExtractor.serverBasePath(api)).isEmpty();   // disagree → refuse to guess servers[0]
    }

    @Test
    void aTemplatedSiblingDoesNotBlockAResolvableServerBase() {
        OpenAPI api = new OpenAPI()
                .addServersItem(new Server().url("https://{env}.bnc.ca/ciam"))   // unresolvable → contributes nothing
                .addServersItem(new Server().url("https://localhost/ciam"));
        assertThat(OpenApiModelExtractor.serverBasePath(api)).isEqualTo("/ciam");
    }

    @Test
    void percentEncodedPathDecodesConsistentlyAcrossTemplatedAndNonTemplatedServers() {
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://{host}/ci%2Fam"))).isEqualTo("/ci/am");
        assertThat(OpenApiModelExtractor.serverBasePath(server("https://host/ci%2Fam"))).isEqualTo("/ci/am");
    }

    private static OpenAPI server(String url) {
        return new OpenAPI().addServersItem(new Server().url(url));
    }
}
