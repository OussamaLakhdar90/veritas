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
    void ignoresATemplatedServerUrl() {
        // A server with a {var} can't yield a static base — leave the path as-is rather than guess.
        assertThat(endpointsOf(widgetSpec("servers:\n  - url: 'https://{host}/api'\n")).get(0).pathTemplate())
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

    private static OpenAPI server(String url) {
        return new OpenAPI().addServersItem(new Server().url(url));
    }
}
