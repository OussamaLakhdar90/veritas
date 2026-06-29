package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.RequestBodyModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The reconcile step sends the LLM a structured per-endpoint model (CODE_API / SPEC_API) instead of terse
 * "VERB /path" signatures. These tests pin the projection that {@link ContractValidationService#apiEvidence} builds —
 * in particular the two provenance distinctions that used to slip into imprecise LLM prose:
 * a HEADER param must be tagged {@code in=header} (not guessed as a query param), and a value set parsed from a
 * parameter's DESCRIPTION must surface as {@code documentedValues}, never a formal {@code enum}.
 */
class ContractValidationEvidenceTest {

    private static final SourceRef SRC = SourceRef.code("Foo.java", 1, 2, "x");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void paramLocationsAndEnumProvenanceAreProjectedFaithfully() throws Exception {
        ParamModel app = new ParamModel("app", ParamLocation.PATH, "string", null, true, ConstraintSet.empty(), SRC);
        ParamModel ctx = new ParamModel(
                "X-Request-Context", ParamLocation.HEADER, "string", null, true, ConstraintSet.empty(), SRC);
        ParamModel documented = new ParamModel("category", ParamLocation.QUERY, "string", null, false,
                ConstraintSet.empty().withEnumFromDescription(List.of("RETAIL", "CORPORATE")), SRC);
        ParamModel formal = new ParamModel("status", ParamLocation.QUERY, "string", null, false,
                ConstraintSet.empty().withEnumValues(List.of("ACTIVE", "INACTIVE")), SRC);
        Endpoint get = new Endpoint(HttpMethod.GET, "/policies/{app}", "getPolicies",
                List.of(app, ctx, documented, formal), null,
                List.of(new ResponseModel(200, "PolicyDTO", List.of("application/json"), "RETURN", SRC)),
                null, List.of("application/json"), List.of(), SRC);

        SchemaModel dto = new SchemaModel("PolicyDTO", "object",
                List.of(
                        new FieldModel("id", "string", "uuid", true, ConstraintSet.empty(), null, SRC),
                        new FieldModel("limit", "integer", null, false,
                                new ConstraintSet(null, null, 0.0, 100.0, null, null, null, null, null), null, SRC)),
                null, SRC);

        ApiModel code = new ApiModel("code", "Policies", "1.0", null, List.of(get), Map.of("PolicyDTO", dto));

        JsonNode ev = mapper.valueToTree(ContractValidationService.apiEvidence(List.of(code)));
        JsonNode params = ev.get("endpoints").get(0).get("params");

        // Exact parameter locations — the header param is a HEADER, the path param a PATH.
        assertThat(findByName(params, "X-Request-Context").get("in").asText()).isEqualTo("header");
        assertThat(findByName(params, "app").get("in").asText()).isEqualTo("path");

        // Description-derived value set => documentedValues, never a formal enum.
        JsonNode documentedNode = findByName(params, "category");
        assertThat(documentedNode.has("documentedValues")).isTrue();
        assertThat(documentedNode.has("enum")).isFalse();
        assertThat(documentedNode.get("documentedValues")).hasSize(2);

        // Formal schema/Java enum => enum, never documentedValues.
        JsonNode formalNode = findByName(params, "status");
        assertThat(formalNode.has("enum")).isTrue();
        assertThat(formalNode.has("documentedValues")).isFalse();

        // Response: status + schema ref + media types survive.
        JsonNode resp = ev.get("endpoints").get(0).get("responses").get(0);
        assertThat(resp.get("status").asInt()).isEqualTo(200);
        assertThat(resp.get("schema").asText()).isEqualTo("PolicyDTO");

        // DTO schema: fields + required flag + numeric constraints projected.
        JsonNode fields = ev.get("schemas").get("PolicyDTO").get("fields");
        assertThat(findByName(fields, "id").get("required").asBoolean()).isTrue();
        JsonNode limit = findByName(fields, "limit");
        assertThat(limit.get("minimum").asDouble()).isEqualTo(0.0);
        assertThat(limit.get("maximum").asDouble()).isEqualTo(100.0);

        // The literal JSON we hand the LLM carries the location and provenance tags.
        String json = mapper.writeValueAsString(ContractValidationService.apiEvidence(List.of(code)));
        assertThat(json).contains("\"in\":\"header\"").contains("\"documentedValues\"");
    }

    @Test
    void requestBodySecurityEmptyConstraintsAndNullModelsAreHandled() {
        Endpoint post = new Endpoint(HttpMethod.POST, "/policies", "create", List.of(),
                new RequestBodyModel("PolicyDTO", true, true, List.of("application/json"), SRC),
                List.of(new ResponseModel(201, "PolicyDTO", List.of("application/json"), "RESPONSE_ENTITY", SRC)),
                List.of("application/json"), null, List.of("policy:write"), SRC);
        ApiModel code = new ApiModel("code", "P", "1", null, List.of(post), Map.of());

        // A null entry in the model list is skipped, not an NPE (spec side can be empty/absent).
        JsonNode ep = mapper.valueToTree(
                ContractValidationService.apiEvidence(Arrays.asList(code, null))).get("endpoints").get(0);

        assertThat(ep.get("requestBody").get("schema").asText()).isEqualTo("PolicyDTO");
        assertThat(ep.get("requestBody").get("required").asBoolean()).isTrue();
        assertThat(ep.get("security").get(0).asText()).isEqualTo("policy:write");
        // No params and no constraints => those keys are simply omitted (kept terse), not emitted empty.
        assertThat(ep.has("params")).isFalse();
    }

    @Test
    void specEndpointsAndSchemasMergeAcrossModels() {
        ApiModel specA = new ApiModel("repo-spec", "A", "1", "3.0.1",
                List.of(new Endpoint(HttpMethod.GET, "/a", "op", List.of(), null, List.of(), null, null, List.of(), SRC)),
                Map.of("Shared", new SchemaModel("Shared", "object", List.of(), null, SRC)));
        ApiModel specB = new ApiModel("confluence-spec", "B", "1", "3.0.1",
                List.of(new Endpoint(HttpMethod.GET, "/b", "op", List.of(), null, List.of(), null, null, List.of(), SRC)),
                Map.of("Other", new SchemaModel("Other", "object", List.of(), null, SRC)));

        JsonNode ev = mapper.valueToTree(ContractValidationService.apiEvidence(List.of(specA, specB)));

        assertThat(ev.get("endpoints")).hasSize(2);
        assertThat(ev.get("schemas").has("Shared")).isTrue();
        assertThat(ev.get("schemas").has("Other")).isTrue();
    }

    private static JsonNode findByName(JsonNode arr, String name) {
        for (JsonNode n : arr) {
            if (name.equals(n.path("name").asText())) {
                return n;
            }
        }
        throw new AssertionError("no element named " + name);
    }
}
