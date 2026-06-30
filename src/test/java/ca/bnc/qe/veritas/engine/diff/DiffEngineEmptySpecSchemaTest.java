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
 * A discovery pass confirmed: a differently-named response/body pair self-suppressed when the SPEC-side schema is a
 * genuinely-empty {type: object} (no blind spot) — hiding the code's documented fields, which the same-named path
 * reports. The structureless guard must distinguish opaque-by-design (composition blind spot) from under-documented.
 */
class DiffEngineEmptySpecSchemaTest {

    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private static FieldModel field(String name) {
        return new FieldModel(name, "string", null, true, ConstraintSet.empty(), null, SRC);
    }

    private static SchemaModel schema(String name, FieldModel... fields) {
        return new SchemaModel(name, "object", List.of(fields), null, SRC);
    }

    private static Endpoint get(String path, String responseRef) {
        return new Endpoint(HttpMethod.GET, path, "op", List.of(), null,
                List.of(new ResponseModel(200, responseRef, List.of("application/json"), "RETURN", SRC)),
                null, null, List.of(), SRC);
    }

    private static List<Finding> diff(ApiModel code, ApiModel spec) {
        return new DiffEngine().diffCodeVsSpec(code, spec);
    }

    @Test
    void genuinelyEmptySpecObjectDoesNotSuppressTheCodesDocumentedFields() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(get("/r", "Resp")),
                Map.of("Resp", schema("Resp", field("id"), field("amount"), field("name"))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(get("/r", "RespSpec")),
                Map.of("RespSpec", schema("RespSpec")), List.of());   // empty {type:object}, NO blind spot

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING
                && f.getSummary() != null && f.getSummary().contains("id"));
    }

    @Test
    void aliasOrExternalStructurelessSpecSchemaStaysSuppressed() {
        // A bare-$ref alias / external $ref resolves to a structureless schema with type=null (NOT a declared empty
        // object) and no blind spot — it must stay suppressed, NOT emit a false SCHEMA_FIELD_MISSING for the code fields.
        ApiModel code = new ApiModel("code", null, null, null, List.of(get("/r", "Resp")),
                Map.of("Resp", schema("Resp", field("id"), field("amount"))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(get("/r", "Alias")),
                Map.of("Alias", new SchemaModel("Alias", null, List.of(), null, SRC)), List.of());

        assertThat(diff(code, spec)).noneMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING);
    }

    @Test
    void opaqueByCompositionSpecSchemaStaysSuppressed() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(get("/r", "Resp")),
                Map.of("Resp", schema("Resp", field("id"), field("amount"))));
        // structureless spec schema, but the extractor recorded a composition blind spot for it → opaque by design.
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(get("/r", "RespSpec")),
                Map.of("RespSpec", schema("RespSpec")),
                List.of("Spec schema 'RespSpec' uses oneOf composition; its composed structure is not fully compared."));

        assertThat(diff(code, spec)).noneMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING);
    }
}
