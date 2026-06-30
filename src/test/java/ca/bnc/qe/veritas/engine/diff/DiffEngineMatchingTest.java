package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/**
 * Diff-engine matching silent-drops the deep review confirmed: an object-vs-array response divergence was suppressed by
 * the coarse guard (normRef strips []), a spec operation under a verb the code lacks was hidden by the path-only dead-spec
 * guard, and distinct spec operations colliding to one normalized key were dropped last-wins.
 */
class DiffEngineMatchingTest {

    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private static FieldModel field(String name, String type) {
        return new FieldModel(name, type, null, true, ConstraintSet.empty(), null, SRC);
    }

    private static SchemaModel schema(String name, FieldModel... fields) {
        return new SchemaModel(name, "object", List.of(fields), null, SRC);
    }

    private static Endpoint ep(HttpMethod verb, String path, String responseRef) {
        List<ResponseModel> resp = responseRef == null ? List.of()
                : List.of(new ResponseModel(200, responseRef, List.of("application/json"), "RETURN", SRC));
        return new Endpoint(verb, path, "op", List.of(), null, resp, List.of(), List.of(), List.of(), SRC);
    }

    private static ApiModel model(String source, List<Endpoint> eps, Map<String, SchemaModel> schemas) {
        return new ApiModel(source, null, null, null, eps, schemas);
    }

    private static List<Finding> diff(ApiModel code, ApiModel spec) {
        return new DiffEngine().diffCodeVsSpec(code, spec);
    }

    @Test
    void objectVsArrayResponseIsNotSilentlyDropped() {
        SchemaModel user = schema("User", field("id", "integer"));
        ApiModel code = model("code", List.of(ep(HttpMethod.GET, "/u", "User")), Map.of("User", user));
        ApiModel spec = model("repo-spec", List.of(ep(HttpMethod.GET, "/u", "User[]")), Map.of("User", user));

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void specVerbNotImplementedInCodeIsFlaggedNotDropped() {
        // code has only GET /x; spec has GET /x AND POST /x → the spec POST must surface (the GET matched, so the old
        // path-only guard silently suppressed the undocumented-in-code POST).
        ApiModel code = model("code", List.of(ep(HttpMethod.GET, "/x", null)), Map.of());
        ApiModel spec = model("repo-spec",
                List.of(ep(HttpMethod.GET, "/x", null), ep(HttpMethod.POST, "/x", null)), Map.of());

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.VERB_MISMATCH
                && f.getSummary() != null && f.getSummary().contains("Spec documents")
                && f.getSummary().contains("POST"));
    }

    @Test
    void matchingVerbOnAPathProducesNoVerbMismatch() {
        ApiModel code = model("code", List.of(ep(HttpMethod.GET, "/x", null)), Map.of());
        ApiModel spec = model("repo-spec", List.of(ep(HttpMethod.GET, "/x", null)), Map.of());

        assertThat(diff(code, spec)).noneMatch(f -> f.getType() == FindingType.VERB_MISMATCH);
    }

    @Test
    void collidingSpecKeysSurfaceAsAmbiguity() {
        ApiModel code = model("code", List.of(ep(HttpMethod.GET, "/orders/{id}", null)), Map.of());
        ApiModel spec = model("repo-spec",
                List.of(ep(HttpMethod.GET, "/orders/{orderId}", null), ep(HttpMethod.GET, "/orders/{id}", null)),
                Map.of());

        assertThat(diff(code, spec)).anyMatch(f -> f.getSummary() != null
                && f.getSummary().contains("collapse to the same normalized signature"));
    }
}
