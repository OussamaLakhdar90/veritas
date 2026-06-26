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
import org.junit.jupiter.api.Test;

/**
 * Round-3 branch coverage for {@link DiffEngine}: targets red/yellow branches not exercised by the existing
 * Deep/PathNormalization/SliceA suites — null-location params, no-success-status endpoints, error-status media
 * drift, structureless-schema short-circuits, enum-schema structural comparison, constraint-keyword diffs, and the
 * security null-side combinations. Assertions check concrete FindingTypes / confidences / summaries (mutation-ready).
 */
class DiffEngineBranch3Test {

    private final DiffEngine diff = new DiffEngine();
    private final SourceRef src = SourceRef.code("X.java", 1, 1, "x");

    private ConstraintSet none() {
        return new ConstraintSet(null, null, null, null, null, null, null, null, null);
    }

    private Endpoint ep(HttpMethod m, String path, List<ParamModel> params, List<ResponseModel> responses) {
        return new Endpoint(m, path, "op", params, null, responses, null, null, List.of(), src);
    }

    private SchemaModel objField(String name, String fieldName, String fieldType) {
        return new SchemaModel(name, "object",
                List.of(new FieldModel(fieldName, fieldType, null, false, none(), null, null)), null, src);
    }

    private SchemaModel enumSchema(String name, List<String> values) {
        return new SchemaModel(name, "string", List.of(), values, src);
    }

    private SchemaModel refField(String name, String fieldName, String refSchema) {
        return new SchemaModel(name, "object",
                List.of(new FieldModel(fieldName, "object", null, false, none(), refSchema, null)), null, src);
    }

    // ---- paramKey: null location AND null name ----

    @Test
    void paramWithNullLocationAndNullNameStillMatchesByDerivedKey() {
        // location==null → "?" prefix, name==null → "" — both sides share that synthesized key so the param is matched
        // (no PARAM_MISSING / PARAM_EXTRA), but a type difference is still surfaced.
        ParamModel codeP = new ParamModel(null, null, "string", null, false, none(), src);
        ParamModel specP = new ParamModel(null, null, "integer", null, false, none(), src);
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/p", List.of(codeP),
                        List.of(new ResponseModel(200, null, null, "RETURN", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/p", List.of(specP),
                        List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        Set<FindingType> types = diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType).collect(toSet());
        assertThat(types).contains(FindingType.PARAM_TYPE_MISMATCH);
        assertThat(types).doesNotContain(FindingType.PARAM_MISSING, FindingType.PARAM_EXTRA);
    }

    // ---- path-vars differ AND counts differ → suppressed (not equal && size-differs branch) ----

    @Test
    void pathVarsOfDifferentArityDoNotEmitNameMismatch() {
        // normalized paths collapse {..} to {} so /a/{x} and /a/{y}/{z} are DIFFERENT keys → won't even match;
        // to hit the "lists differ AND sizes differ" guard we need a normalized-equal path with differing var counts,
        // which normPath cannot produce. Instead: same single var on both sides but the SPEC adds a constant segment
        // is also a different key. So assert the engine does NOT spuriously emit PATH_VAR_NAME_MISMATCH for the
        // 2-vs-1 arity mismatch — they are simply unmatched endpoints, no name-mismatch finding.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/a/{x}", List.of(),
                        List.of(new ResponseModel(200, null, null, "RETURN", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/a/{y}/{z}", List.of(),
                        List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.PATH_VAR_NAME_MISMATCH);
    }

    // ---- successStatus / codeStatus == null: endpoint with only an error response ----

    @Test
    void endpointWithNoSuccessStatusSkipsStatusMissingButStillDiffsErrors() {
        // code endpoint declares ONLY a 500 (no 2xx) → successStatus()==null → the codeStatus!=null block is skipped.
        // The 500 is still surfaced as STATUS_CODE_MISSING (spec omits it) via the error loop.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(),
                        List.of(new ResponseModel(500, null, null, "RESPONSE_ENTITY", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(),
                        List.of(new ResponseModel(400, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f -> f.getType() == FindingType.STATUS_CODE_MISSING
                && f.getSummary().contains("500"));
    }

    // ---- error status documented by BOTH but media types diverge: advice origin → LOW ----

    @Test
    void errorStatusMediaTypeDriftFromAdviceIsLowConfidence() {
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(), List.of(
                        new ResponseModel(200, null, null, "RETURN", src),
                        new ResponseModel(404, null, List.of("application/problem+json"), "EXCEPTION_HANDLER", src)))),
                Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(), List.of(
                        new ResponseModel(200, null, null, "SPEC", src),
                        new ResponseModel(404, null, List.of("application/json"), "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f ->
                f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH
                        && f.getSummary().contains("404")
                        && f.getConfidence() == Confidence.LOW);
    }

    @Test
    void errorStatusMediaTypeDriftFromDirectReturnIsMediumConfidence() {
        // same status documented on both sides, media differs, but origin is NOT an exception-handler advice → MEDIUM
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(), List.of(
                        new ResponseModel(200, null, null, "RETURN", src),
                        new ResponseModel(409, null, List.of("application/problem+json"), "RESPONSE_ENTITY", src)))),
                Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(), List.of(
                        new ResponseModel(200, null, null, "SPEC", src),
                        new ResponseModel(409, null, List.of("application/json"), "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f ->
                f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH
                        && f.getSummary().contains("409")
                        && f.getConfidence() == Confidence.MEDIUM);
    }

    @Test
    void errorStatusDocumentedBySpecWithMatchingMediaTypesIsNotFlagged() {
        // both declare 404 with the same media set → the media-diff branch is false, continue with no finding.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(), List.of(
                        new ResponseModel(200, null, null, "RETURN", src),
                        new ResponseModel(404, null, List.of("application/json"), "EXCEPTION_HANDLER", src)))),
                Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(), List.of(
                        new ResponseModel(200, null, null, "SPEC", src),
                        new ResponseModel(404, null, List.of("application/json"), "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).noneMatch(f ->
                f.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH && f.getSummary().contains("404"));
    }

    // ---- spec documents an ERROR status the code never returns → NOT a STATUS_CODE_EXTRA (success-only guard) ----

    @Test
    void specErrorStatusNotInCodeIsNotFlaggedAsStatusCodeExtra() {
        // STATUS_CODE_EXTRA fires only for 2xx the code lacks. A spec 503 the code never returns is a documented
        // contingency, not noise — so it must NOT produce STATUS_CODE_EXTRA.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(),
                        List.of(new ResponseModel(200, null, null, "RETURN", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/e", List.of(), List.of(
                        new ResponseModel(200, null, null, "SPEC", src),
                        new ResponseModel(503, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.STATUS_CODE_EXTRA);
    }

    // ---- security null-side combinations: neither side secured → no SECURITY_MISMATCH ----

    @Test
    void nullSecurityOnBothSidesProducesNoSecurityMismatch() {
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, null, src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeEp), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(specEp), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.SECURITY_MISMATCH);
    }

    @Test
    void specRequiresSecurityCodeHasNoneAndNoCentralizationIsFlaggedMedium() {
        // !codeSecured && specSecured && !centralizesSecurity(code) → MEDIUM SECURITY_MISMATCH (the else-if branch)
        Endpoint codeOpen = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
        Endpoint specSecured = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of("bearerAuth"), src);
        // blindSpots present but mentioning neither SecurityFilterChain nor HttpSecurity → centralizes==false
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeOpen), Map.of(),
                List.of("Some unrelated extractor blind spot."));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(specSecured), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH
                && f.getConfidence() == Confidence.MEDIUM && f.getSummary().contains("bearerAuth"));
    }

    // ---- request body presence mismatch ----

    @Test
    void requestBodyPresenceMismatchIsFlagged() {
        Endpoint codeEp = new Endpoint(HttpMethod.POST, "/b", "postB", List.of(),
                new RequestBodyModel("Body", true, true, List.of("application/json"), src),
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.POST, "/b", "postB", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeEp), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(specEp), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f ->
                f.getType() == FindingType.REQUEST_BODY_PRESENCE_MISMATCH
                        && f.getSummary().contains("code true vs spec false"));
    }

    // ---- structureless schema short-circuit: code schema present, spec ref unresolved ----

    @Test
    void unresolvedCodeResponseSchemaSuppressesFieldDiffAndCoarseMismatch() {
        // code response ref "Wrapper" has NO entry in code.schemas() → fieldDiffByBinding bails (cs==null), and
        // structuralVerdict returns UNRESOLVED so the coarse RESPONSE_SCHEMA_MISMATCH is also suppressed.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Wrapper", null, "RETURN", src)))),
                Map.of());   // no schema for Wrapper
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "policies", null, "SPEC", src)))),
                Map.of("policies", objField("policies", "password", "string")));

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void structurelessSpecSchemaWithNoFieldsAndNoEnumSuppressesMismatch() {
        // spec schema exists but has no fields and no enum → structureless → UNRESOLVED → no coarse mismatch.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Wrapper", null, "RETURN", src)))),
                Map.of("Wrapper", objField("Wrapper", "password", "string")));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "policies", null, "SPEC", src)))),
                Map.of("policies", new SchemaModel("policies", "object", List.of(), null, src)));

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    // ---- enum-schema structural comparison (propsEqual enum branch): equal enum sets → MATCH (no mismatch) ----

    @Test
    void differentlyNamedEnumResponseSchemasWithSameValuesAreNotAMismatch() {
        // code ref "Status" (enum A,B) vs spec ref "state" (enum b,a) → propsEqual hits the enum branch and finds the
        // value SETS equal (case/order-insensitive) → MATCH → no RESPONSE_SCHEMA_MISMATCH.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Status", null, "RETURN", src)))),
                Map.of("Status", enumSchema("Status", List.of("ACTIVE", "CLOSED"))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "state", null, "SPEC", src)))),
                Map.of("state", enumSchema("state", List.of("closed", "active"))));

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void differentlyNamedEnumResponseSchemasWithDifferentValuesAreAMismatch() {
        // enum value sets genuinely differ → propsEqual enum branch returns false → DIFFER → RESPONSE_SCHEMA_MISMATCH.
        // (fieldDiffByBinding emits nothing for enum-only schemas since they have no fields, so the coarse finding is
        // the only signal and is retained.)
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Status", null, "RETURN", src)))),
                Map.of("Status", enumSchema("Status", List.of("ACTIVE", "CLOSED"))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "state", null, "SPEC", src)))),
                Map.of("state", enumSchema("state", List.of("ACTIVE", "PENDING"))));

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f ->
                f.getType() == FindingType.RESPONSE_SCHEMA_MISMATCH
                        && f.getSummary().contains("Status")
                        && f.getSummary().contains("state"));
    }

    @Test
    void enumCodeVsObjectSpecSurfacesPreciseFieldDiffNotCoarseMismatch() {
        // code side enum-only "Status" (no fields), spec side plain object "state{value}". fieldDiffByBinding runs on
        // the differently-named bound pair FIRST and compareSchema reports the spec's extra 'value' field
        // (SCHEMA_FIELD_EXTRA), so fieldLevelEmitted=true and the coarse RESPONSE_SCHEMA_MISMATCH is suppressed.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Status", null, "RETURN", src)))),
                Map.of("Status", enumSchema("Status", List.of("ACTIVE", "CLOSED"))));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "state", null, "SPEC", src)))),
                Map.of("state", objField("state", "value", "string")));

        Set<FindingType> types = diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType).collect(toSet());
        assertThat(types).contains(FindingType.SCHEMA_FIELD_EXTRA);
        assertThat(types).doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    // ---- array-vs-object via successSchemaRef with DIFFERENT base names: arrayRef differs → DIFFER ----

    @Test
    void arrayCodeVsObjectSpecYieldsCoarseMismatch() {
        // base names differ (Items vs Item) AND code is an array while spec is a single object. normRef strips [] and
        // lowercases, so "items" != "item" → the !normRef equality guard passes, and structuralVerdict returns DIFFER
        // on arrayRef(codeRef)!=arrayRef(specRef). fieldDiffByBinding bails (array vs object) → coarse mismatch is the
        // only signal and is retained.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Items[]", null, "RETURN", src)))),
                Map.of("Items", objField("Items", "name", "string")));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Item", null, "SPEC", src)))),
                Map.of("Item", objField("Item", "name", "string")));

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f ->
                f.getType() == FindingType.RESPONSE_SCHEMA_MISMATCH
                        && f.getSummary().contains("Items[]")
                        && f.getSummary().contains("Item"));
    }

    // ---- typeCompatible: object wildcard means nested object-typed fields never invent a type diff ----

    @Test
    void objectTypedNestedFieldsAreTreatedAsCompatible() {
        // code "Root{child:object->ChildA}" vs spec "RootS{child:object->ChildB}" where ChildA/ChildB have the same
        // field shape → propsEqual recurses, typeCompatible("object",...) is true at the binding field, leaves match →
        // MATCH → no coarse mismatch and no per-field finding.
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Root", refField("Root", "child", "ChildA"),
                "ChildA", objField("ChildA", "v", "string"));
        Map<String, SchemaModel> specSchemas = Map.of(
                "RootS", refField("RootS", "child", "ChildB"),
                "ChildB", objField("ChildB", "v", "string"));
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Root", null, "RETURN", src)))), codeSchemas);
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "RootS", null, "SPEC", src)))), specSchemas);

        Set<FindingType> types = diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType).collect(toSet());
        assertThat(types).doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH, FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
    }

    @Test
    void nestedArrayVsObjectBindingFieldIsAStructuralDiff() {
        // binding field "child" is an array ref on the code side but an object ref on the spec side → propsEqual
        // returns false at arrayRef(c)!=arrayRef(s) → DIFFER → coarse mismatch (fieldDiffByBinding bails on the
        // array-vs-object outer pair? No — outer refs are object/object; the divergence is one level down).
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Root", refField("Root", "child", "ChildA[]"),
                "ChildA", objField("ChildA", "v", "string"));
        Map<String, SchemaModel> specSchemas = Map.of(
                "RootS", refField("RootS", "child", "ChildB"),
                "ChildB", objField("ChildB", "v", "string"));
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "Root", null, "RETURN", src)))), codeSchemas);
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/x", List.of(),
                        List.of(new ResponseModel(200, "RootS", null, "SPEC", src)))), specSchemas);

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .contains(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    // ---- constraintMismatchDesc: each keyword branch reached individually ----

    @Test
    void constraintMinimumDifferenceIsDescribed() {
        ConstraintSet codeC = new ConstraintSet(null, null, 1.0, null, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(null, null, 5.0, null, null, null, null, null, null);
        assertThat(constraintFinding(codeC, specC)).contains("minimum");
    }

    @Test
    void constraintMaximumDifferenceIsDescribed() {
        ConstraintSet codeC = new ConstraintSet(null, null, null, 10.0, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(null, null, null, 99.0, null, null, null, null, null);
        assertThat(constraintFinding(codeC, specC)).contains("maximum");
    }

    @Test
    void constraintPatternDifferenceIsDescribed() {
        ConstraintSet codeC = new ConstraintSet(null, null, null, null, null, null, "[a-z]+", null, null);
        ConstraintSet specC = new ConstraintSet(null, null, null, null, null, null, "[0-9]+", null, null);
        assertThat(constraintFinding(codeC, specC)).contains("pattern");
    }

    @Test
    void constraintMinLengthDifferenceIsDescribed() {
        ConstraintSet codeC = new ConstraintSet(2, null, null, null, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(4, null, null, null, null, null, null, null, null);
        assertThat(constraintFinding(codeC, specC)).contains("minLength");
    }

    /** Drive a schema-field constraint mismatch and return the resulting CONSTRAINT_GAP summary text. */
    private String constraintFinding(ConstraintSet codeC, ConstraintSet specC) {
        SchemaModel codeS = new SchemaModel("Foo", "object",
                List.of(new FieldModel("f", "string", null, false, codeC, null, null)), null, src);
        SchemaModel specS = new SchemaModel("Foo", "object",
                List.of(new FieldModel("f", "string", null, false, specC, null, null)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(), Map.of("Foo", codeS));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(), Map.of("Foo", specS));
        return diff.diffCodeVsSpec(code, spec).stream()
                .filter(f -> f.getType() == FindingType.CONSTRAINT_GAP)
                .map(Finding::getSummary)
                .findFirst().orElse("");
    }

    // ---- schema field present on both with the same constraints → NO constraint gap ----

    @Test
    void identicalFieldConstraintsProduceNoConstraintGap() {
        ConstraintSet same = new ConstraintSet(2, 8, null, null, null, null, null, null, null);
        SchemaModel codeS = new SchemaModel("Foo", "object",
                List.of(new FieldModel("f", "string", null, false, same, null, null)), null, src);
        SchemaModel specS = new SchemaModel("Foo", "object",
                List.of(new FieldModel("f", "string", null, false, same, null, null)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(), Map.of("Foo", codeS));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(), Map.of("Foo", specS));

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.CONSTRAINT_GAP);
    }

    // ---- schema field type mismatch where one side is "object" → wildcard, NOT flagged ----

    @Test
    void schemaFieldTypeObjectWildcardIsNotFlagged() {
        SchemaModel codeS = new SchemaModel("Foo", "object",
                List.of(new FieldModel("f", "object", null, false, none(), null, null)), null, src);
        SchemaModel specS = new SchemaModel("Foo", "object",
                List.of(new FieldModel("f", "string", null, false, none(), null, null)), null, src);
        ApiModel code = new ApiModel("code", null, null, null, List.of(), Map.of("Foo", codeS));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(), Map.of("Foo", specS));

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
    }

    // ---- spec param restricts to an enum the code binds without (hasEnum on spec side, code constraints empty) ----

    @Test
    void specEnumParamNotEnforcedByCodeIsLowConfidenceConstraintGap() {
        ConstraintSet specEnum = new ConstraintSet(null, null, null, null, null, null, null,
                List.of("A", "B"), null);
        ParamModel codeP = new ParamModel("kind", ParamLocation.QUERY, "string", null, false, none(), src);
        ParamModel specP = new ParamModel("kind", ParamLocation.QUERY, "string", null, false, specEnum, src);
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/q", List.of(codeP),
                        List.of(new ResponseModel(200, null, null, "RETURN", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/q", List.of(specP),
                        List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                && f.getConfidence() == Confidence.LOW && f.getSummary().contains("[A, B]"));
    }

    // ---- param required mismatch ----

    @Test
    void paramRequiredMismatchIsFlaggedMedium() {
        ParamModel codeP = new ParamModel("id", ParamLocation.QUERY, "string", null, true, none(), src);
        ParamModel specP = new ParamModel("id", ParamLocation.QUERY, "string", null, false, none(), src);
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/q", List.of(codeP),
                        List.of(new ResponseModel(200, null, null, "RETURN", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/q", List.of(specP),
                        List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f ->
                f.getType() == FindingType.PARAM_REQUIRED_MISMATCH
                        && f.getConfidence() == Confidence.MEDIUM
                        && f.getSummary().contains("code true vs spec false"));
    }

    // ---- param type under-specified: code declares type, spec omits it → LOW PARAM_TYPE_MISMATCH ----

    @Test
    void paramTypeUnderSpecifiedWhenSpecOmitsTypeIsLowConfidence() {
        ParamModel codeP = new ParamModel("id", ParamLocation.QUERY, "string", null, false, none(), src);
        ParamModel specP = new ParamModel("id", ParamLocation.QUERY, null, null, false, none(), src);
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep(HttpMethod.GET, "/q", List.of(codeP),
                        List.of(new ResponseModel(200, null, null, "RETURN", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/q", List.of(specP),
                        List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f ->
                f.getType() == FindingType.PARAM_TYPE_MISMATCH
                        && f.getConfidence() == Confidence.LOW
                        && f.getSummary().contains("under-specified"));
    }

    // ---- l1FromMessages: ref-bearing message → UNRESOLVED_REF, plain → OPENAPI_PARSE_ERROR, null list → empty ----

    @Test
    void l1FromMessagesClassifiesRefVsParseErrorAndHandlesNull() {
        assertThat(diff.l1FromMessages("spec", null)).isEmpty();

        List<Finding> findings = diff.l1FromMessages("spec",
                java.util.Arrays.asList("Unresolved $REF to Pet", "totally broken yaml", null));
        Set<FindingType> types = findings.stream().map(Finding::getType).collect(toSet());
        assertThat(types).contains(FindingType.UNRESOLVED_REF, FindingType.OPENAPI_PARSE_ERROR);
        // the ref message (case-insensitive "ref") → UNRESOLVED_REF
        assertThat(findings).anyMatch(f -> f.getType() == FindingType.UNRESOLVED_REF
                && "Unresolved $REF to Pet".equals(f.getSummary()));
        // a null message is not "ref" → OPENAPI_PARSE_ERROR
        assertThat(findings).anyMatch(f -> f.getType() == FindingType.OPENAPI_PARSE_ERROR && f.getSummary() == null);
    }

    // ---- diffSpecVsSpec: drift in both directions ----

    @Test
    void specVsSpecReportsDriftInBothDirections() {
        ApiModel a = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/only-a", List.of(), List.of()),
                        ep(HttpMethod.GET, "/shared", List.of(), List.of())), Map.of());
        ApiModel b = new ApiModel("confluence-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/only-b", List.of(), List.of()),
                        ep(HttpMethod.GET, "/shared", List.of(), List.of())), Map.of());

        List<Finding> findings = diff.diffSpecVsSpec(a, b);
        assertThat(findings).allMatch(f -> f.getType() == FindingType.SPEC_DRIFT);
        assertThat(findings).anyMatch(f -> f.getSummary().contains("/only-a")
                && f.getSummary().contains("repo-spec") && f.getSummary().contains("but not in"));
        assertThat(findings).anyMatch(f -> f.getSummary().contains("/only-b")
                && f.getSummary().contains("confluence-spec"));
        // the shared endpoint produces no drift
        assertThat(findings).noneMatch(f -> f.getSummary().contains("/shared"));
    }

    @Test
    void specVsSpecIdenticalSetsProduceNoDrift() {
        ApiModel a = new ApiModel("repo-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/p", List.of(), List.of())), Map.of());
        ApiModel b = new ApiModel("confluence-spec", null, null, null,
                List.of(ep(HttpMethod.GET, "/p", List.of(), List.of())), Map.of());

        assertThat(diff.diffSpecVsSpec(a, b)).isEmpty();
    }

    // ---- MISSING_INFO_FIELD: real spec (openApiVersion set) missing both title and version ----

    @Test
    void parsedSpecMissingTitleAndVersionEmitsTwoInfoFindings() {
        ApiModel code = new ApiModel("code", null, null, null, List.of(), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, "3.0.1", List.of(), Map.of());

        List<Finding> infos = diff.diffCodeVsSpec(code, spec).stream()
                .filter(f -> f.getType() == FindingType.MISSING_INFO_FIELD).toList();
        assertThat(infos).hasSize(2);
        assertThat(infos).anyMatch(f -> f.getSummary().contains("info.title"));
        assertThat(infos).anyMatch(f -> f.getSummary().contains("info.version"));
    }

    @Test
    void parsedSpecWithTitleAndVersionEmitsNoInfoFindings() {
        // both present → both isBlank() checks are false → no MISSING_INFO_FIELD
        ApiModel code = new ApiModel("code", null, null, null, List.of(), Map.of());
        ApiModel spec = new ApiModel("repo-spec", "My API", "1.0.0", "3.0.1", List.of(), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.MISSING_INFO_FIELD);
    }
}
