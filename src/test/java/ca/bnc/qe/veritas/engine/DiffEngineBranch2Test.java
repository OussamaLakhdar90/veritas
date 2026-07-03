package ca.bnc.qe.veritas.engine;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
 * Second branch-coverage companion for {@link DiffEngine}, targeting the red/yellow branches the existing
 * {@code DiffEngineBranchTest}/{@code DiffEngineDeepTest}/{@code DiffEnginePathNormalizationTest} do NOT reach:
 * the remaining {@code constraintMismatchDesc} keyword arms (minimum / maximum-only / pattern / enum), the
 * {@code mediaSet} null-entry skip, the structural-equality engine ({@code structuralVerdict}/{@code propsEqual})
 * across enum-vs-object, key-set divergence, nested array-vs-object, deep MATCH recursion, {@code typeCompatible}
 * wildcards on a nested level, cycle guards on both recursive walkers, and the {@code normPath}/{@code pathVars}
 * null/edge inputs. Every assertion checks a real emitted value (type + confidence + summary text) so the cases
 * survive mutation testing rather than just touching the line.
 */
class DiffEngineBranch2Test {

    private final DiffEngine diff = new DiffEngine();
    private final SourceRef src = SourceRef.code("X.java", 1, 1, "x");

    // ---- builders ----

    private ConstraintSet none() {
        return new ConstraintSet(null, null, null, null, null, null, null, null, null);
    }

    private ResponseModel ok(String origin) {
        return new ResponseModel(200, null, null, origin, src);
    }

    private ResponseModel okRef(String origin, String ref) {
        return new ResponseModel(200, ref, null, origin, src);
    }

    private Endpoint get(String path, List<ParamModel> params, List<ResponseModel> responses) {
        return new Endpoint(HttpMethod.GET, path, "op", params, null, responses, null, null, List.of(), src);
    }

    private ApiModel model(String source, List<Endpoint> endpoints, Map<String, SchemaModel> schemas) {
        return new ApiModel(source, null, null, null, endpoints, schemas);
    }

    private SchemaModel schema(String name, List<FieldModel> fields) {
        return new SchemaModel(name, "object", fields, null, src);
    }

    private SchemaModel enumSchema(String name, List<String> values) {
        return new SchemaModel(name, "string", List.of(), values, src);
    }

    private FieldModel field(String json, String type) {
        return new FieldModel(json, type, null, false, none(), null, src);
    }

    private FieldModel fieldC(String json, String type, ConstraintSet c) {
        return new FieldModel(json, type, null, false, c, null, src);
    }

    private FieldModel refField(String json, String refSchema) {
        return new FieldModel(json, "object", null, false, none(), refSchema, src);
    }

    private Set<FindingType> types(List<Finding> findings) {
        return findings.stream().map(Finding::getType).collect(toSet());
    }

    private ApiModel oneParam(String source, ParamModel p) {
        return model(source, List.of(get("/p", List.of(p), List.of(ok("X")))), Map.of());
    }

    private Finding constraintGap(List<Finding> findings) {
        return findings.stream().filter(f -> f.getType() == FindingType.CONSTRAINT_GAP).findFirst().orElseThrow();
    }

    // ---- constraintMismatchDesc: each keyword arm the other tests don't hit ----

    @Test
    void minimumMismatchIsReportedWithBothValues() {
        // minLength/maxLength equal so the desc skips them; the FIRST differing keyword is `minimum`.
        ConstraintSet codeC = new ConstraintSet(null, null, 1.0, null, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(null, null, 5.0, null, null, null, null, null, null);
        Finding f = constraintGap(diff.diffCodeVsSpec(
                oneParam("code", new ParamModel("n", ParamLocation.QUERY, "integer", null, false, codeC, src)),
                oneParam("repo-spec", new ParamModel("n", ParamLocation.QUERY, "integer", null, false, specC, src))));
        assertThat(f.getSummary()).contains("minimum").contains("code=1.0").contains("spec=5.0");
        assertThat(f.getSummary()).doesNotContain("maximum").doesNotContain("pattern");
    }

    @Test
    void maximumOnlyMismatchIsReportedWhenMinimumMatches() {
        // minLength/maxLength/minimum all equal (or null) -> the desc walks down to `maximum`.
        ConstraintSet codeC = new ConstraintSet(null, null, 0.0, 10.0, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(null, null, 0.0, 99.0, null, null, null, null, null);
        Finding f = constraintGap(diff.diffCodeVsSpec(
                oneParam("code", new ParamModel("n", ParamLocation.QUERY, "integer", null, false, codeC, src)),
                oneParam("repo-spec", new ParamModel("n", ParamLocation.QUERY, "integer", null, false, specC, src))));
        assertThat(f.getSummary()).contains("maximum").contains("code=10.0").contains("spec=99.0");
        assertThat(f.getSummary()).doesNotContain("minimum");
    }

    @Test
    void patternMismatchIsReportedWhenAllNumericKeywordsMatch() {
        ConstraintSet codeC = new ConstraintSet(null, null, null, null, null, null, "[a-z]+", null, null);
        ConstraintSet specC = new ConstraintSet(null, null, null, null, null, null, "[A-Z]+", null, null);
        Finding f = constraintGap(diff.diffCodeVsSpec(
                oneParam("code", new ParamModel("s", ParamLocation.QUERY, "string", null, false, codeC, src)),
                oneParam("repo-spec", new ParamModel("s", ParamLocation.QUERY, "string", null, false, specC, src))));
        assertThat(f.getSummary()).contains("pattern").contains("[a-z]+").contains("[A-Z]+");
    }

    @Test
    void enumValueSetMismatchIsReportedWhenEverythingElseMatches() {
        // both non-empty constraint sets; the only differing keyword is the enum value SET -> the last arm fires.
        ConstraintSet codeC = new ConstraintSet(null, null, null, null, null, null, null, List.of("A", "B"), null);
        ConstraintSet specC = new ConstraintSet(null, null, null, null, null, null, null, List.of("A", "C"), null);
        Finding f = constraintGap(diff.diffCodeVsSpec(
                oneParam("code", new ParamModel("s", ParamLocation.QUERY, "string", null, false, codeC, src)),
                oneParam("repo-spec", new ParamModel("s", ParamLocation.QUERY, "string", null, false, specC, src))));
        assertThat(f.getSummary()).contains("enum").contains("A");
    }

    @Test
    void identicalNonEmptyConstraintSetsEmitNoGap() {
        // every keyword equal -> constraintMismatchDesc returns null -> the matched branch adds nothing.
        ConstraintSet c = new ConstraintSet(2, 8, 1.0, 9.0, null, null, "p", List.of("A"), null);
        ConstraintSet c2 = new ConstraintSet(2, 8, 1.0, 9.0, null, null, "p", List.of("a"), null);  // enum case-insensitive
        assertThat(types(diff.diffCodeVsSpec(
                oneParam("code", new ParamModel("s", ParamLocation.QUERY, "string", null, false, c, src)),
                oneParam("repo-spec", new ParamModel("s", ParamLocation.QUERY, "string", null, false, c2, src)))))
                .doesNotContain(FindingType.CONSTRAINT_GAP);
    }

    // ---- mediaSet: null entry inside the media-type list is skipped, not NPE'd ----

    @Test
    void mediaSetSkipsNullEntriesAndStillDetectsDivergence() {
        // code produces [null, application/json]; spec produces [application/xml]. The null is dropped, the remaining
        // base types differ -> a CONSUMES_PRODUCES_MISMATCH is still emitted (proves the null was skipped, not fatal).
        List<String> codeProduces = new ArrayList<>();
        codeProduces.add(null);
        codeProduces.add("application/json");
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/m", "op", List.of(), null,
                List.of(ok("RETURN")), null, codeProduces, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/m", "op", List.of(), null,
                List.of(ok("SPEC")), null, List.of("application/xml"), List.of(), src);
        Finding f = diff.diffCodeVsSpec(model("code", List.of(codeEp), Map.of()),
                        model("repo-spec", List.of(specEp), Map.of()))
                .stream().filter(x -> x.getType() == FindingType.CONSUMES_PRODUCES_MISMATCH).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(f.getSummary()).contains("produces");
    }

    @Test
    void mediaSetOnlyNullEntryOnCodeSideEqualsEmptyAndIsNotDivergence() {
        // code produces [null] -> mediaSet drops it to {} ; spec produces [""] -> also {} -> equal sets -> no finding.
        List<String> codeProduces = new ArrayList<>();
        codeProduces.add(null);
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/m", "op", List.of(), null,
                List.of(ok("RETURN")), null, codeProduces, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/m", "op", List.of(), null,
                List.of(ok("SPEC")), null, List.of("   "), List.of(), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp), Map.of()),
                model("repo-spec", List.of(specEp), Map.of()))))
                .doesNotContain(FindingType.CONSUMES_PRODUCES_MISMATCH);
    }

    // ---- structuralVerdict / propsEqual: enum-vs-object, key-set, nested array, deep MATCH, cycle ----

    private ApiModel codeWithResponse(String ref, Map<String, SchemaModel> schemas) {
        return model("code", List.of(get("/r", List.of(), List.of(okRef("RETURN", ref)))), schemas);
    }

    private ApiModel specWithResponse(String ref, Map<String, SchemaModel> schemas) {
        return model("repo-spec", List.of(get("/r", List.of(), List.of(okRef("SPEC", ref)))), schemas);
    }

    private ApiModel withRequestBody(String source, String origin, String bodyRef, Map<String, SchemaModel> schemas) {
        Endpoint ep = new Endpoint(HttpMethod.POST, "/r", "op", List.of(),
                new RequestBodyModel(bodyRef, true, false, List.of("application/json"), src),
                List.of(ok(origin)), null, null, List.of(), src);
        return model(source, List.of(ep), schemas);
    }

    @Test
    void enumSchemaVsObjectSchemaUnderDifferentNamesIsCoarseResponseMismatch() {
        // code response binds an ENUM-valued schema; spec binds an OBJECT schema. Names differ, neither is structureless,
        // fieldDiffByBinding compares fields: code has none, spec has 'x' -> it emits only SCHEMA_FIELD_EXTRA (a LOW/spec
        // field), but the precise diff DID fire so the coarse mismatch is suppressed. We assert the field-level signal.
        ApiModel code = codeWithResponse("Status", Map.of("Status", enumSchema("Status", List.of("A", "B"))));
        ApiModel spec = specWithResponse("Obj", Map.of("Obj", schema("Obj", List.of(field("x", "string")))));
        Set<FindingType> t = types(diff.diffCodeVsSpec(code, spec));
        assertThat(t).contains(FindingType.SCHEMA_FIELD_EXTRA);
        assertThat(t).doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void differentlyNamedSchemasWithIdenticalSameNamedNestedStructureAreNotAMismatch() {
        // code 'Wrapper' and spec 'Holder' differ by name and bind a SAME-NAMED nested 'Inner{v:string}'. fieldDiffByBinding
        // top level: differently named -> compareSchema(Wrapper vs Holder) on the SAME field-name set {child} with type
        // 'object' (wildcard) -> no field finding; then recurses into child binding Inner|Inner -> SAME name -> compareSchema
        // skipped, nested fields identical -> nothing. structuralVerdict then runs propsEqual which recurses into the same
        // nested pair (typeCompatible on 'v') -> MATCH -> NO coarse mismatch.
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Wrapper", schema("Wrapper", List.of(refField("child", "Inner"))),
                "Inner", schema("Inner", List.of(field("v", "string"))));
        Map<String, SchemaModel> specSchemas = Map.of(
                "Holder", schema("Holder", List.of(refField("child", "Inner"))),
                "Inner", schema("Inner", List.of(field("v", "string"))));
        assertThat(types(diff.diffCodeVsSpec(codeWithResponse("Wrapper", codeSchemas),
                specWithResponse("Holder", specSchemas))))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH, FindingType.SCHEMA_FIELD_MISSING,
                        FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
    }

    @Test
    void nestedFieldKeySetDivergenceIsChargedOnceByTheComponentsLoopNotCoarse() {
        // Differently-named top schemas whose OWN field-name sets are EQUAL (so compareSchema at top emits nothing) but
        // whose SAME-NAMED nested child 'Kid' diverges by key set. The components-schema loop field-diffs the same-named
        // 'Kid' and emits the PRECISE SCHEMA_FIELD_MISSING for 'Kid.a'. fieldDiffByBinding skips compareSchema for that
        // same-named pair (the loop owns it) but now REPORTS the divergence via its return, so the coarse
        // RESPONSE_SCHEMA_MISMATCH is suppressed — one wire defect is charged ONCE (S13j-3), not twice.
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Top", schema("Top", List.of(refField("child", "Kid"))),
                "Kid", schema("Kid", List.of(field("a", "string"))));
        Map<String, SchemaModel> specSchemas = Map.of(
                "TopS", schema("TopS", List.of(refField("child", "Kid"))),
                "Kid", schema("Kid", List.of(field("b", "string"))));   // different key set in the SAME-named nested
        List<Finding> findings =
                diff.diffCodeVsSpec(codeWithResponse("Top", codeSchemas), specWithResponse("TopS", specSchemas));
        assertThat(types(findings)).contains(FindingType.SCHEMA_FIELD_MISSING)
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
        assertThat(findings).filteredOn(x -> x.getType() == FindingType.SCHEMA_FIELD_MISSING)
                .anySatisfy(x -> assertThat(x.getSummary()).contains("Kid").contains("a"));
    }

    @Test
    void nestedArrayVsObjectInsideStructureMakesVerdictDiffer() {
        // Differently named top schemas; their field sets are equal; the SAME-named nested binding field points to an
        // array ref on one side and a plain object ref on the other -> the per-field array-flip check now emits a
        // PRECISE SCHEMA_FIELD_TYPE_MISMATCH at "child" (replacing the coarser response-level mismatch).
        FieldModel codeChild = new FieldModel("child", "object", null, false, none(), "Kid[]", src);
        FieldModel specChild = new FieldModel("child", "object", null, false, none(), "Kid", src);
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Top", schema("Top", List.of(codeChild)),
                "Kid", schema("Kid", List.of(field("a", "string"))));
        Map<String, SchemaModel> specSchemas = Map.of(
                "TopS", schema("TopS", List.of(specChild)),
                "Kid", schema("Kid", List.of(field("a", "string"))));
        assertThat(types(diff.diffCodeVsSpec(codeWithResponse("Top", codeSchemas), specWithResponse("TopS", specSchemas))))
                .contains(FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
    }

    @Test
    void topLevelFieldTypeCompatibleViaObjectWildcardYieldsMatchAndNoMismatch() {
        // Differently-named top schemas; one top field is typed 'object' (wildcard) vs 'string' -> typeCompatible true at
        // the TOP, and no nested refs -> propsEqual MATCH -> NO coarse mismatch. Also compareSchema at top sees the same
        // 'object' wildcard rule -> no SCHEMA_FIELD_TYPE_MISMATCH. Proves the typeCompatible object-wildcard arm.
        Map<String, SchemaModel> codeSchemas = Map.of("A", schema("A", List.of(field("data", "object"))));
        Map<String, SchemaModel> specSchemas = Map.of("B", schema("B", List.of(field("data", "string"))));
        assertThat(types(diff.diffCodeVsSpec(codeWithResponse("A", codeSchemas), specWithResponse("B", specSchemas))))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH, FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
    }

    @Test
    void cyclicSelfReferentialSchemasUnderDifferentNamesTerminateAndMatch() {
        // Each top schema has a field 'self' that refers back to itself -> both the fieldDiffByBinding visited-set guard
        // and the propsEqual visited-set guard must break the cycle. Structures are identical -> NO coarse mismatch and
        // the call terminates (no StackOverflow). Differently named so structuralVerdict is actually consulted.
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Node", schema("Node", List.of(field("v", "string"), refField("self", "Node"))));
        Map<String, SchemaModel> specSchemas = Map.of(
                "NodeS", schema("NodeS", List.of(field("v", "string"), refField("self", "NodeS"))));
        assertThat(types(diff.diffCodeVsSpec(codeWithResponse("Node", codeSchemas), specWithResponse("NodeS", specSchemas))))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void unresolvedNestedRefDoesNotInventADiffAndVerdictStaysMatch() {
        // Differently-named tops, equal field sets; the SAME-named nested binding field refers to a schema absent from
        // BOTH component maps -> propsEqual's nested-resolve guard (nc/ns null) skips it without inventing a diff ->
        // MATCH -> no coarse mismatch. fieldDiffByBinding likewise can't field-diff the missing nested.
        Map<String, SchemaModel> codeSchemas = Map.of("Top", schema("Top", List.of(refField("child", "Ghost"))));
        Map<String, SchemaModel> specSchemas = Map.of("TopS", schema("TopS", List.of(refField("child", "Ghost"))));
        assertThat(types(diff.diffCodeVsSpec(codeWithResponse("Top", codeSchemas), specWithResponse("TopS", specSchemas))))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void nestedFieldTypeMismatchTwoLevelsDeepViaSameNamedChildIsChargedOncePrecisely() {
        // Differently named tops (equal field set); SAME-named nested 'Leaf' with a scalar type divergence (string vs
        // integer, neither 'object'). The components-schema loop field-diffs the same-named 'Leaf' and emits the PRECISE
        // SCHEMA_FIELD_TYPE_MISMATCH for 'Leaf.v'. fieldDiffByBinding skips compareSchema for that same-named pair (the
        // loop owns it) but now REPORTS the divergence via its return, so the coarse RESPONSE_SCHEMA_MISMATCH is
        // suppressed — one wire defect is charged ONCE (S13j-3), not twice.
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Top", schema("Top", List.of(refField("leaf", "Leaf"))),
                "Leaf", schema("Leaf", List.of(field("v", "string"))));
        Map<String, SchemaModel> specSchemas = Map.of(
                "TopS", schema("TopS", List.of(refField("leaf", "Leaf"))),
                "Leaf", schema("Leaf", List.of(field("v", "integer"))));
        List<Finding> findings =
                diff.diffCodeVsSpec(codeWithResponse("Top", codeSchemas), specWithResponse("TopS", specSchemas));
        assertThat(types(findings)).contains(FindingType.SCHEMA_FIELD_TYPE_MISMATCH)
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
        assertThat(findings).filteredOn(x -> x.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH)
                .anySatisfy(x -> assertThat(x.getSummary()).contains("v"));
    }

    @Test
    void requestBodyNestedSameNamedChildDivergenceIsChargedOncePrecisely() {
        // Mirror of the response case on the REQUEST-BODY path: differently-named top body schemas (equal field set)
        // whose SAME-named nested 'Leaf' diverges by scalar type. The components loop emits the precise
        // SCHEMA_FIELD_TYPE_MISMATCH for 'Leaf.v'; fieldDiffByBinding reports the same-named divergence via its return
        // so the coarse REQUEST_BODY_SCHEMA_MISMATCH is suppressed — one wire defect charged ONCE, not twice.
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Body", schema("Body", List.of(refField("leaf", "Leaf"))),
                "Leaf", schema("Leaf", List.of(field("v", "string"))));
        Map<String, SchemaModel> specSchemas = Map.of(
                "BodyS", schema("BodyS", List.of(refField("leaf", "Leaf"))),
                "Leaf", schema("Leaf", List.of(field("v", "integer"))));
        List<Finding> findings = diff.diffCodeVsSpec(
                withRequestBody("code", "RETURN", "Body", codeSchemas),
                withRequestBody("repo-spec", "SPEC", "BodyS", specSchemas));
        assertThat(types(findings)).contains(FindingType.SCHEMA_FIELD_TYPE_MISMATCH)
                .doesNotContain(FindingType.REQUEST_BODY_SCHEMA_MISMATCH);
        assertThat(findings).filteredOn(x -> x.getType() == FindingType.SCHEMA_FIELD_TYPE_MISMATCH)
                .anySatisfy(x -> assertThat(x.getSummary()).contains("v"));
    }

    @Test
    void structurelessSpecSchemaUnderDifferentNamesSuppressesCoarseMismatch() {
        // code binds a structured schema, spec binds a structureless one (no fields, no enum) under a different name ->
        // structuralVerdict returns UNRESOLVED -> coarse mismatch suppressed (the != DIFFER guard). Proves the
        // structureless arm of both fieldDiffByBinding and structuralVerdict.
        Map<String, SchemaModel> codeSchemas = Map.of("A", schema("A", List.of(field("x", "string"))));
        Map<String, SchemaModel> specSchemas = Map.of("B", schema("B", List.of()));   // no fields, no enum -> structureless
        assertThat(types(diff.diffCodeVsSpec(codeWithResponse("A", codeSchemas), specWithResponse("B", specSchemas))))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    // ---- same-name components-schema loop still reaches the field/constraint arms (compareSchema standalone) ----

    @Test
    void schemaFieldMissingFromSpecInSameNamedComponentsLoopIsHigh() {
        // a code field absent from the same-named spec schema -> SCHEMA_FIELD_MISSING (HIGH). Exercises the
        // compareSchema branch where specFields.get(...) == null.
        ApiModel code = model("code", List.of(),
                Map.of("User", schema("User", List.of(field("ssn", "string")))));
        ApiModel spec = model("repo-spec", List.of(),
                Map.of("User", schema("User", List.of())));
        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.SCHEMA_FIELD_MISSING).findFirst().orElseThrow();
        assertThat(f.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(f.getSummary()).contains("ssn");
    }

    @Test
    void schemaFieldConstraintMinimumMismatchInComponentsLoop() {
        // drive constraintMismatchDesc's `minimum` arm through the schema-field path (not the param path).
        ConstraintSet codeC = new ConstraintSet(null, null, 0.0, null, null, null, null, null, null);
        ConstraintSet specC = new ConstraintSet(null, null, 7.0, null, null, null, null, null, null);
        ApiModel code = model("code", List.of(),
                Map.of("M", schema("M", List.of(fieldC("age", "integer", codeC)))));
        ApiModel spec = model("repo-spec", List.of(),
                Map.of("M", schema("M", List.of(fieldC("age", "integer", specC)))));
        Finding f = diff.diffCodeVsSpec(code, spec).stream()
                .filter(x -> x.getType() == FindingType.CONSTRAINT_GAP).findFirst().orElseThrow();
        assertThat(f.getSummary()).contains("minimum").contains("code=0.0").contains("spec=7.0");
    }

    // ---- normPath / pathVars null + edge inputs ----

    @Test
    void nullCodePathMatchesNullSpecPathBothNormalizeToRoot() {
        // pathTemplate null on both sides -> normPath returns "/" for each -> they match by key -> no MISSING/EXTRA,
        // and pathVars(null) returns empty for both -> no PATH_VAR_NAME_MISMATCH. Exercises both null guards.
        Endpoint codeEp = new Endpoint(HttpMethod.GET, null, "op", List.of(), null,
                List.of(ok("RETURN")), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, null, "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp), Map.of()),
                model("repo-spec", List.of(specEp), Map.of()))))
                .doesNotContain(FindingType.MISSING_ENDPOINT, FindingType.EXTRA_ENDPOINT,
                        FindingType.PATH_VAR_NAME_MISMATCH);
    }

    @Test
    void emptyPathNormalizesToRootAndMatchesSlash() {
        // code path "" -> normPath -> "/" ; spec path "/" -> "/" : they match (no false MISSING/EXTRA). Hits the
        // `p.isEmpty() ? "/" : p` empty-after-trim arm and the slash side.
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "", "op", List.of(), null,
                List.of(ok("RETURN")), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        assertThat(types(diff.diffCodeVsSpec(model("code", List.of(codeEp), Map.of()),
                model("repo-spec", List.of(specEp), Map.of()))))
                .doesNotContain(FindingType.MISSING_ENDPOINT, FindingType.EXTRA_ENDPOINT);
    }

    @Test
    void rootSlashPathIsNotTrimmedAndStillMatches() {
        // a lone "/" must NOT be stripped to "" (the length>1 guard) -> both sides stay "/" and match.
        Endpoint codeEp = new Endpoint(HttpMethod.GET, "/", "op", List.of(), null,
                List.of(ok("RETURN")), null, null, List.of(), src);
        Endpoint specEp = new Endpoint(HttpMethod.GET, "/", "op", List.of(), null,
                List.of(ok("SPEC")), null, null, List.of(), src);
        assertThat(diff.diffCodeVsSpec(model("code", List.of(codeEp), Map.of()),
                model("repo-spec", List.of(specEp), Map.of()))).isEmpty();
    }
}