package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/** Slice A diff fixes: parameter location is part of identity, spec under-specification is flagged, info fields. */
class DiffEngineSliceATest {

    private final DiffEngine diff = new DiffEngine();
    private final SourceRef src = SourceRef.code("X.java", 1, 1, "x");
    private final ConstraintSet empty = new ConstraintSet(null, null, null, null, null, null, null, null, null);

    private Endpoint ep(HttpMethod m, String path, List<ParamModel> params) {
        return new Endpoint(m, path, "op", params, null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
    }

    private ParamModel p(String name, ParamLocation loc, String type) {
        return new ParamModel(name, loc, type, null, false, empty, src);
    }

    @Test
    void parameterLocationIsPartOfIdentity() {
        // code declares a QUERY 'id'; the spec declares a HEADER 'id' — same name, different location → NOT a match.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/things", List.of(p("id", ParamLocation.QUERY, "string")))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", "T", "1.0", "3.0.3",
                List.of(ep(HttpMethod.GET, "/things", List.of(p("id", ParamLocation.HEADER, "string")))), Map.of());

        List<Finding> f = diff.diffCodeVsSpec(code, spec);

        assertThat(f).anyMatch(x -> x.getType() == FindingType.PARAM_MISSING && x.getSummary().contains("QUERY"));
        assertThat(f).anyMatch(x -> x.getType() == FindingType.PARAM_EXTRA && x.getSummary().contains("HEADER"));
    }

    @Test
    void specOmittingParamTypeIsFlaggedAsUnderSpecified() {
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(p("q", ParamLocation.QUERY, "integer")))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", "T", "1.0", "3.0.3",
                List.of(ep(HttpMethod.GET, "/x", List.of(p("q", ParamLocation.QUERY, null)))), Map.of());

        List<Finding> f = diff.diffCodeVsSpec(code, spec);

        assertThat(f).anyMatch(x -> x.getType() == FindingType.PARAM_TYPE_MISMATCH
                && x.getSummary().contains("under-specified"));
    }

    @Test
    void realSpecMissingInfoTitleAndVersionIsFlagged() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep(HttpMethod.GET, "/x", List.of())), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, "3.0.3", List.of(ep(HttpMethod.GET, "/x", List.of())), Map.of());

        long infos = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.MISSING_INFO_FIELD).count();

        assertThat(infos).isEqualTo(2);   // info.title + info.version
    }

    @Test
    void codeWithoutOpenApiVersionDoesNotTriggerInfoFindings() {
        // A synthetic spec (openApiVersion null) is not a real parsed document → no info-field noise.
        ApiModel code = new ApiModel("code", null, null, null, List.of(ep(HttpMethod.GET, "/x", List.of())), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(ep(HttpMethod.GET, "/x", List.of())), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec))
                .noneMatch(x -> x.getType() == FindingType.MISSING_INFO_FIELD);
    }
}
