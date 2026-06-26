package ca.bnc.qe.veritas.engine;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import org.junit.jupiter.api.Test;

/**
 * Mutation-killing tests for {@link DiffEngine}. Each test picks inputs that make a specific surviving mutant
 * produce a DIFFERENT observable finding than the original, then asserts the exact correct value. New file —
 * no existing test or production code is modified.
 */
class DiffEngineMutationTest {

    private final DiffEngine diff = new DiffEngine();
    private final SourceRef src = SourceRef.code("X.java", 1, 1, "x");
    private final ConstraintSet empty = new ConstraintSet(null, null, null, null, null, null, null, null, null);

    // ---- fixture helpers ----

    private Endpoint ep(HttpMethod m, String path, List<ParamModel> params, List<ResponseModel> responses) {
        return new Endpoint(m, path, "op", params, null, responses, null, null, List.of(), src);
    }

    private Endpoint epR(String path, List<ResponseModel> responses) {
        return new Endpoint(HttpMethod.GET, path, "op", List.of(), null, responses, null, null, List.of(), src);
    }

    private ResponseModel ok() {
        return new ResponseModel(200, null, null, "RETURN", src);
    }

    private ResponseModel resp(int status, String origin) {
        return new ResponseModel(status, null, null, origin, src);
    }

    private ParamModel p(String name, ParamLocation loc, String type, boolean required) {
        return new ParamModel(name, loc, type, null, required, empty, src);
    }

    private ApiModel model(String source, Endpoint... eps) {
        return new ApiModel(source, null, null, null, List.of(eps), Map.of());
    }

    private SchemaModel schema(String name, String fieldName, String fieldType) {
        return new SchemaModel(name, "object",
                List.of(new FieldModel(fieldName, fieldType, null, false, empty, null, null)), null, src);
    }

    private SchemaModel objRef(String name, String field, String refSchema) {
        return new SchemaModel(name, "object",
                List.of(new FieldModel(field, "object", null, false, empty, refSchema, null)), null, src);
    }

    private Set<FindingType> types(List<Finding> f) {
        return f.stream().map(Finding::getType).collect(toSet());
    }

    // ... (33 @Test methods — full content on disk at the testFilePath) ...
}