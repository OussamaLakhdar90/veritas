package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.RequestBodyModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/**
 * Structural-verdict silent drops a discovery pass confirmed: a request-body array-vs-object cardinality mismatch had no
 * coarse fallback (unlike the response path) and no finding type; a nested array-vs-object field flip was dropped by the
 * recursion's early return; and a scalar(String)-vs-object response folded into UNRESOLVED instead of DIFFER.
 */
class DiffEngineStructuralVerdictTest {

    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private static FieldModel scalar(String name, String type) {
        return new FieldModel(name, type, null, true, ConstraintSet.empty(), null, SRC);
    }

    private static FieldModel ref(String name, String refSchema) {
        return new FieldModel(name, "array".equals(refSchema) ? "array" : "object", null, false,
                ConstraintSet.empty(), refSchema, SRC);
    }

    private static SchemaModel schema(String name, FieldModel... fields) {
        return new SchemaModel(name, "object", List.of(fields), null, SRC);
    }

    private static Endpoint post(String path, String bodyRef) {
        return new Endpoint(HttpMethod.POST, path, "op", List.of(),
                new RequestBodyModel(bodyRef, true, false, List.of("application/json"), SRC),
                List.of(), null, null, List.of(), SRC);
    }

    private static Endpoint get(String path, String responseRef) {
        return new Endpoint(HttpMethod.GET, path, "op", List.of(), null,
                List.of(new ResponseModel(200, responseRef, List.of("application/json"), "RETURN", SRC)),
                null, null, List.of(), SRC);
    }

    private static ApiModel model(String src, List<Endpoint> eps, Map<String, SchemaModel> schemas) {
        return new ApiModel(src, null, null, null, eps, schemas);
    }

    private static List<Finding> diff(ApiModel code, ApiModel spec) {
        return new DiffEngine().diffCodeVsSpec(code, spec);
    }

    @Test
    void requestBodyArrayVsObjectCardinalityIsFlagged() {
        SchemaModel item = schema("Item", scalar("sku", "string"));
        ApiModel code = model("code", List.of(post("/x", "Item[]")), Map.of("Item", item));
        ApiModel spec = model("repo-spec", List.of(post("/x", "Item")), Map.of("Item", item));

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.REQUEST_BODY_SCHEMA_MISMATCH);
    }

    @Test
    void matchingRequestBodyCardinalityProducesNoSchemaMismatch() {
        SchemaModel item = schema("Item", scalar("sku", "string"));
        ApiModel code = model("code", List.of(post("/x", "Item[]")), Map.of("Item", item));
        ApiModel spec = model("repo-spec", List.of(post("/x", "Item[]")), Map.of("Item", item));

        assertThat(diff(code, spec)).noneMatch(f -> f.getType() == FindingType.REQUEST_BODY_SCHEMA_MISMATCH);
    }

    @Test
    void nestedArrayVsObjectFieldFlipIsFlaggedEvenWhenTheEnclosingSchemaSharesAName() {
        SchemaModel kid = schema("Kid", scalar("id", "string"));
        SchemaModel codeOrder = schema("Order", ref("items", "Kid[]"));   // items is an array of Kid
        SchemaModel specOrder = schema("Order", ref("items", "Kid"));     // items is a single Kid
        ApiModel code = model("code", List.of(get("/o", "Order")), Map.of("Order", codeOrder, "Kid", kid));
        ApiModel spec = model("repo-spec", List.of(get("/o", "Order")), Map.of("Order", specOrder, "Kid", kid));

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH
                && f.getSummary() != null && f.getSummary().contains("items"));
    }

    @Test
    void scalarVsObjectResponseIsFlaggedNotDropped() {
        SchemaModel account = schema("Account", scalar("id", "string"));
        ApiModel code = model("code", List.of(get("/a", "String")), Map.of());          // bare String body
        ApiModel spec = model("repo-spec", List.of(get("/a", "Account")), Map.of("Account", account));

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void scalarVsScalarResponseProducesNoFalseMismatch() {
        ApiModel code = model("code", List.of(get("/a", "String")), Map.of());
        ApiModel spec = model("repo-spec", List.of(get("/a", "string")), Map.of());

        assertThat(diff(code, spec)).noneMatch(f -> f.getType() == FindingType.RESPONSE_SCHEMA_MISMATCH);
    }
}
