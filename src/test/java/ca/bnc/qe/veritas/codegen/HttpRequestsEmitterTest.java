package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import org.junit.jupiter.api.Test;

/** The .http emitter produces one IntelliJ/Bruno request per endpoint, deterministically, no prohibited tools. */
class HttpRequestsEmitterTest {

    private final HttpRequestsEmitter emitter = new HttpRequestsEmitter();
    private final SourceRef src = SourceRef.code("X.java", 1, 1, "x");

    @Test
    void emitsOneRequestPerEndpoint() {
        Endpoint get = new Endpoint(HttpMethod.GET, "/policies/{id}", "get", List.of(), null,
                List.of(), null, null, List.of(), src);
        Endpoint post = new Endpoint(HttpMethod.POST, "/policies", "create", List.of(), null,
                List.of(), null, null, List.of("hasRole('ADMIN')"), src);
        ApiModel model = new ApiModel("code", null, null, null, List.of(get, post), Map.of());

        String http = emitter.emit("ciam-policies", model);

        assertThat(http).contains("GET {{baseUrl}}/policies/{id}");
        assertThat(http).contains("POST {{baseUrl}}/policies");
        assertThat(http).contains("Content-Type: application/json");        // POST gets a body
        assertThat(http).contains("Authorization: Bearer {{token}}");       // secured endpoint
        assertThat(http.toLowerCase()).doesNotContain("postman").doesNotContain("newman");
    }
}
