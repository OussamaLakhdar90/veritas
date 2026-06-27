package ca.bnc.qe.veritas.codegen.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.RequestBodyModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;

/** Scoping an ApiModel to selected endpoints, keeping the transitive schemas they reference. */
class EndpointScopeTest {

    private static FieldModel field(String json, String refSchema) {
        return new FieldModel(json, "object", null, false, null, refSchema, null);
    }

    private static SchemaModel schema(String name, FieldModel... fields) {
        return new SchemaModel(name, "object", List.of(fields), List.of(), null);
    }

    private static Endpoint post(String path, String reqRef, String respRef) {
        return new Endpoint(HttpMethod.POST, path, "POST " + path, List.of(),
                new RequestBodyModel(reqRef, true, true, List.of("application/json"), null),
                List.of(new ResponseModel(201, respRef, List.of("application/json"), "RETURN", null)),
                List.of(), List.of(), List.of(), null);
    }

    private static Endpoint get(String path) {
        return new Endpoint(HttpMethod.GET, path, "GET " + path, List.of(), null,
                List.of(new ResponseModel(200, null, List.of(), "RETURN", null)),
                List.of(), List.of(), List.of(), null);
    }

    private ApiModel model() {
        return new ApiModel("code", "svc", "1", null,
                List.of(post("/policies", "PolicyDTO", "PolicyDTO"), get("/health")),
                Map.of("PolicyDTO", schema("PolicyDTO", field("address", "AddressDTO")),
                        "AddressDTO", schema("AddressDTO"),
                        "UnrelatedDTO", schema("UnrelatedDTO")),
                List.of("some blind spot"));
    }

    @Test
    void keepsOnlyTheSelectedEndpointAndTheSchemasItReaches() {
        ApiModel scoped = EndpointScope.filter(model(), Set.of("POST /policies"));

        assertThat(scoped.endpoints()).extracting(Endpoint::signature).containsExactly("POST /policies");
        // PolicyDTO + its nested AddressDTO are kept; the unrelated DTO is dropped.
        assertThat(scoped.schemas()).containsOnlyKeys("PolicyDTO", "AddressDTO");
        assertThat(scoped.blindSpots()).containsExactly("some blind spot");   // preserved
    }

    @Test
    void emptyScopeReturnsTheModelUnchanged() {
        ApiModel full = model();
        assertThat(EndpointScope.filter(full, Set.of())).isSameAs(full);
        assertThat(EndpointScope.filter(full, null)).isSameAs(full);
    }

    @Test
    void unselectedEndpointsAndTheirSchemasAreDropped() {
        ApiModel scoped = EndpointScope.filter(model(), Set.of("GET /health"));

        assertThat(scoped.endpoints()).extracting(Endpoint::signature).containsExactly("GET /health");
        assertThat(scoped.schemas()).isEmpty();   // /health references no schema
    }

    @Test
    void nullModelIsReturnedAsIs() {
        assertThat(EndpointScope.filter(null, Set.of("x"))).isNull();
    }
}
