package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
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

/** Deep L4 diff: response-schema mismatch, extra spec status code, and constraint VALUE mismatch. */
class DiffEngineDeepTest {

    private final DiffEngine diff = new DiffEngine();
    private final SourceRef src = SourceRef.code("X.java", 1, 1, "x");

    private Endpoint ep(String ref, List<ResponseModel> responses) {
        return new Endpoint(HttpMethod.GET, "/x", "getX", List.of(), null, responses, null, null, List.of(), src);
    }

    private SchemaModel foo(Integer maxLen) {
        ConstraintSet c = new ConstraintSet(null, maxLen, null, null, null, null, null, null, null);
        return new SchemaModel("Foo", "object",
                List.of(new FieldModel("name", "string", null, false, c, null, null)), null, src);
    }

    @Test
    void detectsResponseSchemaAndStatusAndConstraintValueDiffs() {
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep("Foo", List.of(new ResponseModel(200, "Foo", null, "RETURN", src)))),
                Map.of("Foo", foo(10)));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep("Bar", List.of(
                        new ResponseModel(200, "Bar", null, "SPEC", src),
                        new ResponseModel(202, null, null, "SPEC", src)))),
                Map.of("Foo", foo(20)));

        List<Finding> findings = diff.diffCodeVsSpec(code, spec);
        Set<FindingType> types = findings.stream().map(Finding::getType).collect(toSet());

        assertThat(types).contains(
                FindingType.RESPONSE_SCHEMA_MISMATCH,   // code returns Foo, spec declares Bar
                FindingType.STATUS_CODE_EXTRA,          // spec documents 202, code never returns it
                FindingType.CONSTRAINT_GAP);            // maxLength 10 (code) vs 20 (spec)
        assertThat(findings).anyMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                && f.getSummary().contains("maxLength"));
    }

    @Test
    void detectsSecurityMismatchWhenCodeEnforcesAuthButSpecDoesNot() {
        Endpoint secured = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null,
                List.of("hasRole('ADMIN')"), src);
        Endpoint open = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(secured), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(open), Map.of());

        List<Finding> findings = diff.diffCodeVsSpec(code, spec);
        assertThat(findings).anyMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH
                && f.getSummary().contains("ADMIN"));
    }
}
