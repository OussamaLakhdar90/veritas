package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.RequestBodyModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;

/**
 * Media-type comparison false positives a discovery pass confirmed: a code `produces` error media (problem+json) the
 * spec lists on an error response was flagged (the 2xx filter applies to the spec side only); a multipart request-body
 * content type was never diffed; and wildcard / +suffix media types were compared by exact set-equality.
 */
class DiffEngineMediaTypeTest {

    private static final SourceRef SRC = SourceRef.code("X.java", 1, 1, "x");

    private static ResponseModel resp(int status, List<String> media) {
        return new ResponseModel(status, null, media, "RETURN", SRC);
    }

    private static Endpoint get(String path, List<String> produces, List<ResponseModel> responses) {
        return new Endpoint(HttpMethod.GET, path, "op", List.of(), null, responses, null, produces, List.of(), SRC);
    }

    private static Endpoint postBody(String path, List<String> bodyMedia) {
        return new Endpoint(HttpMethod.POST, path, "op", List.of(),
                new RequestBodyModel(null, true, false, bodyMedia, SRC),
                List.of(resp(200, null)), null, null, List.of(), SRC);
    }

    private static ApiModel model(String src, Endpoint ep) {
        return new ApiModel(src, null, null, null, List.of(ep), Map.of());
    }

    private static List<Finding> diff(ApiModel code, ApiModel spec) {
        return new DiffEngine().diffCodeVsSpec(code, spec);
    }

    @Test
    void codeProducesErrorMediaDocumentedOnASpecErrorResponseIsNotAMismatch() {
        ApiModel code = model("code", get("/x",
                List.of("application/json", "application/problem+json"), List.of(resp(200, List.of("application/json")))));
        ApiModel spec = model("repo-spec", get("/x",
                List.of("application/json"),
                List.of(resp(200, List.of("application/json")), resp(500, List.of("application/problem+json")))));

        assertThat(diff(code, spec)).noneMatch(f -> f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH);
    }

    @Test
    void wildcardSuffixMediaTypesAreCompatibleNotAMismatch() {
        ApiModel code = model("code", get("/y",
                List.of("application/*+json"), List.of(resp(200, List.of("application/*+json")))));
        ApiModel spec = model("repo-spec", get("/y",
                List.of("application/json"), List.of(resp(200, List.of("application/json")))));

        assertThat(diff(code, spec)).noneMatch(f -> f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH);
    }

    @Test
    void aGenuineProducesDivergenceStillFires() {
        ApiModel code = model("code", get("/z",
                List.of("application/xml"), List.of(resp(200, List.of("application/xml")))));
        ApiModel spec = model("repo-spec", get("/z",
                List.of("application/json"), List.of(resp(200, List.of("application/json")))));

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH);
    }

    @Test
    void multipartRequestBodyContentTypeIsCompared() {
        ApiModel code = model("code", postBody("/upload", List.of("multipart/form-data")));
        ApiModel spec = model("repo-spec", postBody("/upload", List.of("application/json")));

        assertThat(diff(code, spec)).anyMatch(f -> f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH
                && f.getSummary() != null && f.getSummary().contains("request body content"));
    }
}
