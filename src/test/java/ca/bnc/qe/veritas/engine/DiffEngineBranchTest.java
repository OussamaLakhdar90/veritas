package ca.bnc.qe.veritas.engine;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

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
import ca.bnc.qe.veritas.engine.model.RequestBodyModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage companion to {@link DiffEngineDeepTest}. Builds code + spec {@link ApiModel}s directly and
 * asserts the exact {@link FindingType}/{@link Confidence}/{@link Severity} emitted for each diff-rule edge case
 * the deep test does not already exercise: verb mismatch, missing/extra endpoint, path-var name mismatch, all four
 * param diffs by location, request-body presence, status-code missing/extra at each tier, consumes/produces drift,
 * error media-type drift, security in both directions, constraint gaps, schema field diffs, spec-vs-spec drift,
 * and the L1 parser-message + info-field branches.
 */
class DiffEngineBranchTest {

    private final DiffEngine diff = new DiffEngine();
    private final SourceRef src = SourceRef.code("X.java", 1, 1, "x");

    private ConstraintSet none() {
        return new ConstraintSet(null, null, null, null, null, null, null, null, null);
    }

    private ResponseModel ok(String origin) {
        return new ResponseModel(200, null, null, origin, src);
    }

    private Endpoint get(String path, List<ParamModel> params, RequestBodyModel body,
                         List<ResponseModel> responses, List<String> security) {
        return new Endpoint(HttpMethod.GET, path, "op", params, body, responses, null, null, security, src);
    }

    private ApiModel model(String source, List<Endpoint> endpoints) {
        return new ApiModel(source, null, null, null, endpoints, Map.of());
    }

    private Set<FindingType> types(List<Finding> findings) {
        return findings.stream().map(Finding::getType).collect(toSet());
    }

    // ---- L1 info-field branches ----

    @Test
    void specWithOpenApiVersionButBlankTitleAndVersionEmitsTwoInfoFields() {
        ApiModel code = model("code", List.of());
        // openApiVersion non-null marks it a real parsed spec -> title + version are checked.
        ApiModel spec = new ApiModel("repo-spec", "  ", null, "3.0.1", List.of(), Map.of());

        List<Finding> findings = diff.diffCodeVsSpec(code, spec);
        List<Finding> info = findings.stream()
                .filter(f -> f.getType() == FindingType.MISSING_INFO_FIELD).toList();
        assertThat(info).hasSize(2);
        assertThat(info).allMatch(f -> f.getLayer() == Layer.L1);
        assertThat(info).allMatch(f -> f.getSeverity() == Severity.INFO);
        assertThat(info).allMatch(f -> f.getConfidence() == Confidence.MEDIUM);
        assertThat(info).anyMatch(f -> f.getSummary().contains("info.title"));
        assertThat(info).anyMatch(f -> f.getSummary().contains("info.version"));
    }

    @Test
    void specWithTitleAndVersionPresentEmitsNoInfoField() {
        ApiModel code = model("code", List.of());
        ApiModel spec = new ApiModel("repo-spec", "My API", "1.0.0", "3.0.1", List.of(), Map.of());
        assertThat(types(diff.diffCodeVsSpec(code, spec))).doesNotContain(FindingType.MISSING_INFO_FIELD);
    }

    @Test
    void codeApiModelWithNullOpenApiVersionSkipsInfoFieldCheck() {
        // openApiVersion == null -> not a parsed spec -> title/version never checked even though both are blank.
        ApiModel code = model("code", List.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(), Map.of());
        assertThat(types(diff.diffCodeVsSpec(code, spec))).doesNotContain(FindingType.MISSING_INFO_FIELD);
    }

    // ---- endpoint coverage branches ----

    @Test
    void samePathDifferentVerbIsVerbMismatchNotMissing() {
        Endpoint codeGet = get("/users/{id}", List.of(), null, List.of(ok("RETURN")), List.of());
        Endpoint specPut = new Endpoint(HttpMethod.PUT, "/users/{id}", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        List<Finding> findings = diff.diffCodeVsSpec(model("code", List.of(codeGet)), model("repo-spec", List.of(specPut)));

        Finding verb = findings.stream().filter(f -> f.getType() == FindingType.VERB_MISMATCH).findFirst().orElseThrow();
        assertThat(verb.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(verb.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(verb.getLayer()).isEqualTo(Layer.L4);
        assertThat(verb.getSummary()).contains("GET").contains("PUT").contains("/users/{id}");
        assertThat(types(findings)).doesNotContain(FindingType.MISSING_ENDPOINT);
    }

    @Test
    void codeEndpointAbsentFromSpecIsMissingEndpoint() {
        Endpoint codeOnly = get("/only-code", List.of(), null, List.of(ok("RETURN")), List.of());
        List<Finding> findings = diff.diffCodeVsSpec(model("code", List.of(codeOnly)), model("repo-spec", List.of()));

        Finding miss = findings.stream().filter(f -> f.getType() == FindingType.MISSING_ENDPOINT).findFirst().orElseThrow();
        assertThat(miss.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(miss.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(miss.getLayer()).isEqualTo(Layer.L2);
        assertThat(miss.getSummary()).contains("GET /only-code");
    }

    @Test
    void specEndpointAbsentFromCodeIsExtraEndpoint() {
        Endpoint specOnly = new Endpoint(HttpMethod.GET, "/only-spec", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        List<Finding> findings = diff.diffCodeVsSpec(model("code", List.of()), model("repo-spec", List.of(specOnly)));

        Finding extra = findings.stream().filter(f -> f.getType() == FindingType.EXTRA_ENDPOINT).findFirst().orElseThrow();
        assertThat(extra.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(extra.getSeverity()).isEqualTo(Severity.MINOR);
        assertThat(extra.getLayer()).isEqualTo(Layer.L3);
        assertThat(extra.getSummary()).contains("dead spec");
    }

    @Test
    void specEndpointMatchedByPathOnlyDifferentVerbIsNotExtraEndpoint() {
        // code GET /p ; spec POST /p -> the spec POST is reachable by path, so it is NOT flagged EXTRA (only VERB).
        Endpoint codeGet = get("/p", List.of(), null, List.of(ok("RETURN")), List.of());
        Endpoint specPost = new Endpoint(HttpMethod.POST, "/p", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        Set<FindingType> t = types(diff.diffCodeVsSpec(model("code", List.of(codeGet)), model("repo-spec", List.of(specPost))));
        assertThat(t).contains(FindingType.VERB_MISMATCH).doesNotContain(FindingType.EXTRA_ENDPOINT);
    }

    // ---- path variable name mismatch ----

    @Test
    void pathVarNameMismatchSameArityIsFlaggedMinor() {
        Endpoint codeEp = get("/a/{app}", List.of(
                new ParamModel("app", ParamLocation.PATH, "string", null, true, none(), src)),
                null, List.of(ok("RETURN")), List.of());
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/a/{appId}", "op", List.of(
                new ParamModel("appId", ParamLocation.PATH, "string", null, true, none(), src)),
                null, List.of(ok("SPEC")), null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.PATH_VAR_NAME_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(f.getSeverity()).isEqualTo(Severity.MINOR);   // positional -> non-breaking
        assertThat(f.getSummary()).contains("[app]").contains("[appId]");
    }

    @Test
    void pathVarDifferentArityIsNotPathVarNameMismatch() {
        // {a}/{b} vs {a} -> different arity; both normalise to "/p/{}/{}" vs "/p/{}" so they don't even match by key.
        Endpoint codeEp = get("/p/{a}/{b}", List.of(), null, List.of(ok("RETURN")), List.of());
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p/{a}", "op", List.of(),
                null, List.of(ok("SPEC")), null, null, List.of(), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))))
                .doesNotContain(FindingType.PATH_VAR_NAME_MISMATCH);
    }

    // ---- parameter diffs ----

    private ApiModel oneEpParams(String source, String origin, List<ParamModel> params) {
        Endpoint ep = new Endpoint(HttpMethod.GET, "/p", "op", params, null,
                List.of(ok(origin)), null, null, List.of(), src);
        return model(source, List.of(ep));
    }

    @Test
    void queryParamInCodeMissingFromSpecIsParamMissingHigh() {
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("q", ParamLocation.QUERY, "string", null, false, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC", List.of());

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.PARAM_MISSING).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(f.getSeverity()).isEqualTo(Severity.MAJOR);
        assertThat(f.getSummary()).contains("'q'").contains("QUERY");
    }

    @Test
    void queryParamInSpecNotInCodeIsParamExtraMedium() {
        ApiModel code = oneEpParams("code", "RETURN", List.of());
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("page", ParamLocation.QUERY, "integer", null, false, none(), src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.PARAM_EXTRA).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(f.getSummary()).contains("'page'").contains("QUERY");
    }

    @Test
    void sameNameDifferentLocationIsBothMissingAndExtraNotAMatch() {
        // code query 'id' vs spec header 'id' -- keyed by location so they never match: MISSING (code) + EXTRA (spec).
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("id", ParamLocation.QUERY, "string", null, false, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("id", ParamLocation.HEADER, "string", null, false, none(), src)));
        Set<FindingType> t = types(diff.diffCodeVsSpec(code, spec));
        assertThat(t).contains(FindingType.PARAM_MISSING, FindingType.PARAM_EXTRA);
    }

    @Test
    void paramTypeMismatchWhenBothDeclareDifferentTypesIsMedium() {
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("n", ParamLocation.QUERY, "integer", null, false, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("n", ParamLocation.QUERY, "string", null, false, none(), src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.PARAM_TYPE_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(f.getSummary()).contains("code integer").contains("spec string");
    }

    @Test
    void paramTypeUnderSpecifiedWhenSpecOmitsTypeIsLow() {
        // code declares a type, spec param exists but has null type -> under-specification, LOW confidence.
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("n", ParamLocation.QUERY, "integer", null, false, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("n", ParamLocation.QUERY, null, null, false, none(), src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.PARAM_TYPE_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(f.getSummary()).contains("under-specified");
    }

    @Test
    void paramRequiredMismatchIsFlaggedMedium() {
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("q", ParamLocation.QUERY, "string", null, true, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("q", ParamLocation.QUERY, "string", null, false, none(), src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.PARAM_REQUIRED_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(f.getSummary()).contains("code true").contains("spec false");
    }

    @Test
    void codePathParamIsSkippedNotReportedMissing() {
        // PATH params are covered by the path itself + PATH_VAR_NAME_MISMATCH, so they are skipped in param diff.
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("id", ParamLocation.PATH, "string", null, true, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC", List.of());
        assertThat(types(diff.diffCodeVsSpec(code, spec))).doesNotContain(FindingType.PARAM_MISSING);
    }

    @Test
    void specPathParamIsSkippedNotReportedExtra() {
        ApiModel code = oneEpParams("code", "RETURN", List.of());
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("id", ParamLocation.PATH, "string", null, true, none(), src)));
        assertThat(types(diff.diffCodeVsSpec(code, spec))).doesNotContain(FindingType.PARAM_EXTRA);
    }

    // ---- parameter constraint gaps ----

    @Test
    void paramWithCodeConstraintsAbsentFromSpecIsConstraintGap() {
        ConstraintSet codeC = new ConstraintSet(2, 8, null, null, null, null, null, null, null);
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("pw", ParamLocation.QUERY, "string", null, false, codeC, src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("pw", ParamLocation.QUERY, "string", null, false, none(), src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.CONSTRAINT_GAP).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(f.getSummary()).contains("not exposed in the spec");
    }

    @Test
    void paramConstraintValueMismatchIsConstraintGap() {
        ConstraintSet codeC = new ConstraintSet(2, null, null, null, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(4, null, null, null, null, null, null, null, null);
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("pw", ParamLocation.QUERY, "string", null, false, codeC, src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("pw", ParamLocation.QUERY, "string", null, false, specC, src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.CONSTRAINT_GAP).findFirst().orElseThrow();
        assertThat(f.getSummary()).contains("minLength").contains("code=2").contains("spec=4");
    }

    @Test
    void paramConstraintsEquivalentEmitsNoGap() {
        ConstraintSet codeC = new ConstraintSet(2, 8, null, null, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(2, 8, null, null, null, null, null, null, null);
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("pw", ParamLocation.QUERY, "string", null, false, codeC, src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("pw", ParamLocation.QUERY, "string", null, false, specC, src)));
        assertThat(types(diff.diffCodeVsSpec(code, spec))).doesNotContain(FindingType.CONSTRAINT_GAP);
    }

    @Test
    void specEnumOnParamCodeBindsWithoutEnumIsLowConstraintGap() {
        // code param has NO constraints but the spec restricts it to an enum -> allowed values not enforced at boundary.
        ConstraintSet specC = new ConstraintSet(null, null, null, null, null, null, null,
                List.of("ACTIVE", "CLOSED"), null);
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("status", ParamLocation.QUERY, "string", null, false, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("status", ParamLocation.QUERY, "string", null, false, specC, src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.CONSTRAINT_GAP).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(f.getSummary()).contains("ACTIVE").contains("not enforced at the API boundary");
    }

    @Test
    void specEnumFromDescriptionIsWordedAsDocumentedNotRestricted() {
        // The allowed set is only in the parameter's DESCRIPTION prose (not a formal schema enum), so the finding must
        // say it's "documented" — never claim the spec "restricts" it (the spec-enum-framing imprecision).
        ConstraintSet specC = ConstraintSet.empty().withEnumFromDescription(List.of("ACTIVE", "CLOSED"));
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("status", ParamLocation.QUERY, "string", null, false, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("status", ParamLocation.QUERY, "string", null, false, specC, src)));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.CONSTRAINT_GAP).findFirst().orElseThrow();
        assertThat(f.getSummary())
                .contains("description documents the allowed values")
                .contains("doesn't enforce them at the API boundary")
                .doesNotContain("the spec restricts it to");
    }

    @Test
    void specNonEnumConstraintWithNoCodeConstraintEmitsNoGap() {
        // spec has only a maxLength (no enum) and code has none -> the enum-mirror branch does NOT fire (scoped to enums).
        ConstraintSet specC = new ConstraintSet(null, 50, null, null, null, null, null, null, null);
        ApiModel code = oneEpParams("code", "RETURN",
                List.of(new ParamModel("note", ParamLocation.QUERY, "string", null, false, none(), src)));
        ApiModel spec = oneEpParams("repo-spec", "SPEC",
                List.of(new ParamModel("note", ParamLocation.QUERY, "string", null, false, specC, src)));
        assertThat(types(diff.diffCodeVsSpec(code, spec))).doesNotContain(FindingType.CONSTRAINT_GAP);
    }

    // ---- request body presence ----

    @Test
    void requestBodyInCodeNotInSpecIsPresenceMismatchHigh() {
        RequestBodyModel body = new RequestBodyModel("Req", true, true, List.of("application/json"), src);
        Endpoint codeEp = new Endpoint(HttpMethod.POST, "/p", "op", List.of(), body,
                List.of(ok("RETURN")), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.POST, "/p", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.REQUEST_BODY_PRESENCE_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(f.getSeverity()).isEqualTo(Severity.MAJOR);
        assertThat(f.getSummary()).contains("code true").contains("spec false");
    }

    @Test
    void requestBodyPresentOnBothSidesEmitsNoPresenceMismatch() {
        RequestBodyModel body = new RequestBodyModel("Req", true, true, List.of("application/json"), src);
        Endpoint codeEp = new Endpoint(HttpMethod.POST, "/p", "op", List.of(), body,
                List.of(ok("RETURN")), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.POST, "/p", "op", List.of(), body,
                List.of(ok("SPEC")), null, null, List.of(), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))))
                .doesNotContain(FindingType.REQUEST_BODY_PRESENCE_MISMATCH);
    }

    // ---- status codes ----

    @Test
    void codeSuccessStatusNotDocumentedBySpecIsStatusMissingMedium() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(new ResponseModel(201, null, null, "RETURN", src)), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.STATUS_CODE_MISSING && x.getSummary().contains("201"))
                .findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void exceptionHandlerReachableErrorStatusIsMediumConfidence() {
        // EXCEPTION_HANDLER_REACHABLE is the proven-reachable advice path -> MEDIUM (scored), unlike blanket advice.
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("RETURN"), new ResponseModel(409, null, null, "EXCEPTION_HANDLER_REACHABLE", src)),
                null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.STATUS_CODE_MISSING && x.getSummary().contains("409"))
                .findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void specExtraSuccessStatusNotReturnedByCodeIsStatusExtraLow() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src),
                        new ResponseModel(204, null, null, "SPEC", src)), null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.STATUS_CODE_EXTRA).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(f.getSummary()).contains("204");
    }

    @Test
    void specExtraErrorStatusNotReturnedByCodeIsNotFlagged() {
        // a spec ERROR code (>=400) the code doesn't return is a documented contingency -> no STATUS_CODE_EXTRA.
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src),
                        new ResponseModel(503, null, null, "SPEC", src)), null, null, List.of(), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))))
                .doesNotContain(FindingType.STATUS_CODE_EXTRA);
    }

    @Test
    void errorStatusDocumentedButMediaTypeDriftFromNonAdviceIsMedium() {
        // both document 400, but the media types differ and the code origin is NOT advice -> MEDIUM CONSUMES_PRODUCES.
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("RETURN"),
                        new ResponseModel(400, null, List.of("application/json"), "RESPONSE_ENTITY", src)),
                null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("SPEC"),
                        new ResponseModel(400, null, List.of("application/problem+json"), "SPEC", src)),
                null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(f.getSummary()).contains("400").contains("media type");
    }

    @Test
    void errorStatusMediaTypeDriftFromAdviceIsLow() {
        // same media drift but origin starts with EXCEPTION_HANDLER -> advice -> LOW confidence.
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("RETURN"),
                        new ResponseModel(400, null, List.of("application/problem+json"), "EXCEPTION_HANDLER", src)),
                null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("SPEC"),
                        new ResponseModel(400, null, List.of("application/json"), "SPEC", src)),
                null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void errorStatusDocumentedWithMatchingMediaTypesEmitsNothing() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("RETURN"),
                        new ResponseModel(400, null, List.of("application/json"), "RESPONSE_ENTITY", src)),
                null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(), null,
                List.of(ok("SPEC"),
                        new ResponseModel(400, null, List.of("application/json"), "SPEC", src)),
                null, null, List.of(), src);
        Set<FindingType> t = types(diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp))));
        assertThat(t).doesNotContain(FindingType.CONSUMES_PRODUCES_MISMATCH, FindingType.STATUS_CODE_MISSING);
    }

    // ---- security ----

    @Test
    void codeSecuredSpecOpenIsSecurityMismatchHighCritical() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("RETURN")), null, null, List.of("hasRole('ADMIN')"), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.SECURITY_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(f.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.getSummary()).contains("ADMIN").contains("declares no security");
    }

    @Test
    void specSecuredCodeOpenWithoutCentralizationIsSecurityMismatchMedium() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("RETURN")), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of("bearerAuth"), src);
        // no blind spots -> no centralization -> the mismatch fires.
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeEp), Map.of(), List.of());

        Finding f = diff.diffCodeVsSpec(code, model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.SECURITY_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(f.getSummary()).contains("bearerAuth").contains("enforces none");
    }

    @Test
    void specSecuredCodeCentralizesViaHttpSecurityBlindSpotIsSuppressed() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("RETURN")), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of("bearerAuth"), src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeEp), Map.of(),
                List.of("Authz centralized in an HttpSecurity bean."));
        assertThat(types(diff.diffCodeVsSpec(code, model("repo-spec", List.of(specEp)))))
                .doesNotContain(FindingType.SECURITY_MISMATCH);
    }

    @Test
    void bothSidesSecuredEmitsNoSecurityMismatch() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("RETURN")), null, null, List.of("hasRole('ADMIN')"), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/s", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of("bearerAuth"), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))))
                .doesNotContain(FindingType.SECURITY_MISMATCH);
    }

    // ---- consumes/produces ----

    @Test
    void consumesMediaTypeDivergenceIsFlaggedLow() {
        Endpoint codeEp = new Endpoint(HttpMethod.POST, "/m", "op", List.of(), null,
                List.of(ok("RETURN")), List.of("application/json"), null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.POST, "/m", "op", List.of(), null,
                List.of(ok("SPEC")), List.of("application/xml"), null, List.of(), src);

        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))
                .stream().filter(x -> x.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(f.getSummary()).contains("consumes");
    }

    @Test
    void consumesNotFlaggedWhenSpecDeclaresNone() {
        // code declares consumes but the spec declares none -> guard returns early, no finding.
        Endpoint codeEp = new Endpoint(HttpMethod.POST, "/m", "op", List.of(), null,
                List.of(ok("RETURN")), List.of("application/json"), null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.POST, "/m", "op", List.of(), null,
                List.of(ok("SPEC")), List.of(), null, List.of(), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))))
                .doesNotContain(FindingType.CONSUMES_PRODUCES_MISMATCH);
    }

    // ---- schema field-level diffs (same-name schema loop) ----

    private SchemaModel schema(String name, List<FieldModel> fields) {
        return new SchemaModel(name, "object", fields, null, src);
    }

    private FieldModel field(String json, String type, ConstraintSet c) {
        return new FieldModel(json, type, null, false, c == null ? none() : c, null, src);
    }

    @Test
    void sameNamedSchemaFieldTypeMismatchIsFlaggedMediumMajor() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("age", "integer", null)))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("age", "string", null)))));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(f.getSeverity()).isEqualTo(Severity.MAJOR);
        assertThat(f.getSummary()).contains("code integer").contains("spec string");
    }

    @Test
    void sameNamedSchemaFieldTypeWildcardObjectIsNotMismatch() {
        // a field typed "object" on either side is a wildcard -> no SCHEMA_FIELD_TYPE_MISMATCH.
        ApiModel code = new ApiModel("code", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("data", "object", null)))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("data", "string", null)))));
        assertThat(types(diff.diffCodeVsSpec(code, spec))).doesNotContain(FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
    }

    @Test
    void schemaFieldOnlyInSpecIsFieldExtraLowMinor() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("id", "integer", null)))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(
                        field("id", "integer", null), field("nickname", "string", null)))));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.SCHEMA_FIELD_EXTRA).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(f.getSeverity()).isEqualTo(Severity.MINOR);
        assertThat(f.getSummary()).contains("nickname");
    }

    @Test
    void schemaFieldConstraintGapWhenSpecOmitsConstraint() {
        ConstraintSet codeC = new ConstraintSet(3, null, null, null, null, null, null, null, null);
        ApiModel code = new ApiModel("code", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("name", "string", codeC)))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("name", "string", null)))));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.CONSTRAINT_GAP && x.getSummary().contains("name"))
                .findFirst().orElseThrow();
        assertThat(f.getSummary()).contains("not exposed in the spec");
    }

    @Test
    void schemaFieldConstraintValueMismatchIsConstraintGap() {
        ConstraintSet codeC = new ConstraintSet(null, null, null, 99.0, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(null, null, null, 10.0, null, null, null, null, null);
        ApiModel code = new ApiModel("code", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("score", "number", codeC)))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(),
                Map.of("User", schema("User", List.of(field("score", "number", specC)))));

        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.CONSTRAINT_GAP).findFirst().orElseThrow();
        assertThat(f.getSummary()).contains("maximum").contains("code=99.0").contains("spec=10.0");
    }

    @Test
    void schemaPresentOnlyInCodeIsNotFieldDiffed() {
        // a schema with no spec counterpart is never compared (the loop only walks names present on both sides).
        ApiModel code = new ApiModel("code", null, null, null, List.of(),
                Map.of("OnlyCode", schema("OnlyCode", List.of(field("x", "string", null)))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(), Map.of());
        assertThat(types(diff.diffCodeVsSpec(code, spec)))
                .doesNotContain(FindingType.SCHEMA_FIELD_MISSING, FindingType.SCHEMA_FIELD_EXTRA);
    }

    // ---- spec vs spec ----

    @Test
    void diffSpecVsSpecFlagsDriftInBothDirections() {
        Endpoint inA = new Endpoint(HttpMethod.GET, "/a-only", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        Endpoint shared = new Endpoint(HttpMethod.GET, "/shared", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        Endpoint inB = new Endpoint(HttpMethod.GET, "/b-only", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        ApiModel a = model("repo-spec", List.of(inA, shared));
        ApiModel b = model("confluence-spec", List.of(shared, inB));

        List<Finding> findings = diff.diffSpecVsSpec(a, b);
        assertThat(findings).allMatch(f -> f.getType() == FindingType.SPEC_DRIFT);
        assertThat(findings).allMatch(f -> f.getConfidence() == Confidence.HIGH);
        assertThat(findings).allMatch(f -> f.getLayer() == Layer.L3);
        assertThat(findings).anyMatch(f -> f.getSummary().contains("/a-only") && f.getSummary().contains("not in confluence-spec"));
        assertThat(findings).anyMatch(f -> f.getSummary().contains("/b-only") && f.getSummary().contains("not in repo-spec"));
        // /shared appears in both -> no drift for it.
        assertThat(findings).noneMatch(f -> f.getSummary().contains("/shared"));
    }

    @Test
    void diffSpecVsSpecIdenticalEmitsNothing() {
        Endpoint e = new Endpoint(HttpMethod.GET, "/x", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        assertThat(diff.diffSpecVsSpec(model("a", List.of(e)), model("b", List.of(e)))).isEmpty();
    }

    // ---- l1FromMessages ----

    @Test
    void l1FromMessagesClassifiesRefAsUnresolvedRefElseParseError() {
        List<Finding> findings = diff.l1FromMessages("repo-spec",
                List.of("Unresolvable $ref to #/components/schemas/Missing", "Invalid OpenAPI document structure"));
        Finding refF = findings.stream().filter(f -> f.getSummary().contains("$ref")).findFirst().orElseThrow();
        Finding parseF = findings.stream().filter(f -> f.getSummary().contains("structure")).findFirst().orElseThrow();
        assertThat(refF.getType()).isEqualTo(FindingType.UNRESOLVED_REF);
        assertThat(parseF.getType()).isEqualTo(FindingType.OPENAPI_PARSE_ERROR);
        assertThat(findings).allMatch(f -> f.getConfidence() == Confidence.HIGH);
        assertThat(findings).allMatch(f -> f.getLayer() == Layer.L1);
        assertThat(findings).allMatch(f -> f.getSeverity() == Severity.BLOCKER);
    }

    @Test
    void l1FromMessagesNullListReturnsEmpty() {
        assertThat(diff.l1FromMessages("repo-spec", null)).isEmpty();
    }

    @Test
    void l1FromMessagesNullMessageDefaultsToParseError() {
        // a null entry can't contain "ref" -> the ternary's else branch -> OPENAPI_PARSE_ERROR.
        List<String> msgs = new java.util.ArrayList<>();
        msgs.add(null);
        List<Finding> findings = diff.l1FromMessages("repo-spec", msgs);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getType()).isEqualTo(FindingType.OPENAPI_PARSE_ERROR);
    }

    // ---- dedup ----

    @Test
    void diffCodeVsSpecNeverReturnsTwoFindingsWithTheSameId() {
        // dedup() collapses exact-duplicate findingIds. Build a rich diff (verb, params, status, security, schema)
        // and assert every returned findingId is distinct -- i.e. nothing is double-counted for one locus.
        ParamModel cp = new ParamModel("q", ParamLocation.QUERY, "integer", null, true, none(), src);
        ParamModel sp = new ParamModel("q", ParamLocation.QUERY, "string", null, false, none(), src);
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(cp), null,
                List.of(new ResponseModel(201, null, null, "RETURN", src)), null, null, List.of("hasRole('A')"), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(sp), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeEp),
                Map.of("User", schema("User", List.of(field("age", "integer", null)))));
        ApiModel spec = new ApiModel("repo-spec", null, null, "3.0.1", List.of(specEp),
                Map.of("User", schema("User", List.of(field("age", "string", null)))));

        List<Finding> findings = diff.diffCodeVsSpec(code, spec);
        assertThat(findings).isNotEmpty();
        assertThat(findings).extracting(Finding::getFindingId).doesNotHaveDuplicates();
    }

    // ---- matched endpoint with no differences ----

    @Test
    void fullyMatchingEndpointsEmitNoFindings() {
        ParamModel p = new ParamModel("q", ParamLocation.QUERY, "string", null, false, none(), src);
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(p), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/p", "op", List.of(p), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);
        assertThat(diff.diffCodeVsSpec(model("code", List.of(codeEp)), model("repo-spec", List.of(specEp)))).isEmpty();
    }
}