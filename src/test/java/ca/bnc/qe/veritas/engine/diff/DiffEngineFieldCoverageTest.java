package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.RequestBodyModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/**
 * Field-level coverage holes an audit flagged: request payloads got ZERO field-level validation (presence only), and
 * the schema-field diff never compared {@code required} or {@code format}. These are asymmetries with the response /
 * param paths, so they silently passed real contract breaks.
 */
class DiffEngineFieldCoverageTest {

    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private static FieldModel field(String name, String type, String format, boolean required) {
        return new FieldModel(name, type, format, required, ConstraintSet.empty(), null, SRC);
    }

    private static SchemaModel schema(String name, FieldModel... fields) {
        return new SchemaModel(name, "object", List.of(fields), null, SRC);
    }

    private static Endpoint post(String path, String bodyRef) {
        return new Endpoint(HttpMethod.POST, path, "op", List.of(),
                new RequestBodyModel(bodyRef, true, false, List.of("application/json"), SRC),
                List.of(), null, null, List.of(), SRC);
    }

    private static List<Finding> diff(ApiModel code, ApiModel spec) {
        return new DiffEngine().diffCodeVsSpec(code, spec);
    }

    @Test
    void requestBodyFieldDiffSurfacesAMissingBodyField() {
        // Distinct body-schema names → only the request-body BINDING path can field-compare them (the same-name
        // component loop never fires); both non-empty so neither self-suppresses as structureless.
        ApiModel code = new ApiModel("code", null, null, null, List.of(post("/x", "CodeReq")),
                Map.of("CodeReq", schema("CodeReq", field("amount", "integer", null, true))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(post("/x", "SpecReq")),
                Map.of("SpecReq", schema("SpecReq", field("other", "string", null, false))));

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING
                && f.getSummary() != null && f.getSummary().contains("amount"));
    }

    @Test
    void schemaFieldRequiredDriftIsFlagged() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(post("/x", "CodeReq")),
                Map.of("CodeReq", schema("CodeReq", field("email", "string", null, true))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(post("/x", "SpecReq")),
                Map.of("SpecReq", schema("SpecReq", field("email", "string", null, false))));

        assertThat(diff(code, spec)).anyMatch(f -> f.getSummary() != null
                && f.getSummary().contains("email") && f.getSummary().contains("required"));
    }

    @Test
    void schemaFieldRequiredIsNotFlaggedWhenCodeLacksTheAnnotation() {
        // code field is NOT @NotNull (required=false) while the spec lists it required — this is NOT reliable drift
        // (the code may validate in the service/constructor), so it must stay silent, not false-flag every conforming
        // spec-required field. Only the faithful direction (code required, spec optional) fires.
        ApiModel code = new ApiModel("code", null, null, null, List.of(post("/x", "CodeReq")),
                Map.of("CodeReq", schema("CodeReq", field("email", "string", null, false))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(post("/x", "SpecReq")),
                Map.of("SpecReq", schema("SpecReq", field("email", "string", null, true))));

        assertThat(diff(code, spec)).noneMatch(f -> f.getSummary() != null
                && f.getSummary().contains("email") && f.getSummary().contains("required"));
    }

    @Test
    void schemaFieldFormatDriftIsFlagged() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(post("/x", "CodeReq")),
                Map.of("CodeReq", schema("CodeReq", field("id", "integer", "int64", false))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(post("/x", "SpecReq")),
                Map.of("SpecReq", schema("SpecReq", field("id", "integer", "int32", false))));

        assertThat(diff(code, spec)).anyMatch(f -> f.getSummary() != null
                && f.getSummary().contains("id") && f.getSummary().contains("format"));
    }

    @Test
    void paramFormatDriftIsFlagged() {
        ParamModel codeP = new ParamModel("since", ParamLocation.QUERY, "string", "date-time", false,
                ConstraintSet.empty(), SRC);
        ParamModel specP = new ParamModel("since", ParamLocation.QUERY, "string", "date", false,
                ConstraintSet.empty(), SRC);
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/x", "op", List.of(codeP), null, List.of(),
                null, null, List.of(), SRC);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/x", "op", List.of(specP), null, List.of(),
                null, null, List.of(), SRC);
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeEp), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(specEp), Map.of());

        assertThat(diff(code, spec)).anyMatch(f -> f.getSummary() != null
                && f.getSummary().contains("since") && f.getSummary().contains("format"));
    }
}
