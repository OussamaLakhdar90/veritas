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
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.config.GateProperties;
import ca.bnc.qe.veritas.contract.ReleaseVerdict;
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

    private SchemaModel schema(String name, String fieldName, String fieldType) {
        ConstraintSet none = new ConstraintSet(null, null, null, null, null, null, null, null, null);
        return new SchemaModel(name, "object",
                List.of(new FieldModel(fieldName, fieldType, null, false, none, null, null)), null, src);
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
                // Bar has a genuinely different field set than Foo (id vs name) → structural DIFFER
                Map.of("Foo", foo(20), "Bar", schema("Bar", "id", "integer")));

        List<Finding> findings = diff.diffCodeVsSpec(code, spec);
        Set<FindingType> types = findings.stream().map(Finding::getType).collect(toSet());

        // code Foo{name} vs spec Bar{id} → the precise field-level diff fires (name missing / id extra); the coarse
        // RESPONSE_SCHEMA_MISMATCH is suppressed so the one shape defect isn't double-penalised.
        assertThat(types).contains(
                FindingType.SCHEMA_FIELD_MISSING,       // 'name' is in code but not the spec schema
                FindingType.STATUS_CODE_EXTRA,          // spec documents 202, code never returns it
                FindingType.CONSTRAINT_GAP);            // maxLength 10 (code) vs 20 (spec)
        assertThat(types).doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
        assertThat(findings).anyMatch(f -> f.getType() == FindingType.CONSTRAINT_GAP
                && f.getSummary().contains("maxLength"));
    }

    @Test
    void identicalStructureUnderDifferentNamesIsNotAResponseSchemaMismatch() {
        // code DTO "Wrapper{password}" and spec schema "policies{password}" serialize to the same wire shape —
        // the differing schema NAME is not a contract break (regression guard for the false positive).
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep("Wrapper", List.of(new ResponseModel(200, "Wrapper", null, "RETURN", src)))),
                Map.of("Wrapper", schema("Wrapper", "password", "string")));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep("policies", List.of(new ResponseModel(200, "policies", null, "SPEC", src)))),
                Map.of("policies", schema("policies", "password", "string")));

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void genuinelyDifferentStructureIsStillFlaggedAcrossNames() {
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep("Wrapper", List.of(new ResponseModel(200, "Wrapper", null, "RETURN", src)))),
                Map.of("Wrapper", schema("Wrapper", "password", "string")));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep("policies", List.of(new ResponseModel(200, "policies", null, "SPEC", src)))),
                Map.of("policies", schema("policies", "token", "string")));   // different field name

        // Real divergence still surfaces — as the precise field-level finding (password in code, not the spec) — and
        // the now-redundant coarse RESPONSE_SCHEMA_MISMATCH is suppressed so it isn't double-counted.
        Set<FindingType> types = diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType).collect(toSet());
        assertThat(types).contains(FindingType.SCHEMA_FIELD_MISSING);
        assertThat(types).doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void arrayVsObjectResponseStillEmitsTheCoarseMismatch() {
        // code returns Foo[] but the spec declares a single Bar object — fieldDiffByBinding can't field-diff an array
        // against an object (it returns early), so the coarse RESPONSE_SCHEMA_MISMATCH is the ONLY signal and is kept.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep("Foo[]", List.of(new ResponseModel(200, "Foo[]", null, "RETURN", src)))),
                Map.of("Foo", schema("Foo", "name", "string")));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep("Bar", List.of(new ResponseModel(200, "Bar", null, "SPEC", src)))),
                Map.of("Bar", schema("Bar", "name", "string")));

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .contains(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void scoreCountsOneResponseShapeDefectOnceNotTwice() {
        // code Foo{name} vs spec Bar{id}: ONE response-shape defect. It must cost a single MAJOR (the
        // SCHEMA_FIELD_MISSING for 'name'), not a MAJOR for that PLUS a MAJOR for the redundant coarse mismatch.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep("Foo", List.of(new ResponseModel(200, "Foo", null, "RETURN", src)))),
                Map.of("Foo", schema("Foo", "name", "string")));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep("Bar", List.of(new ResponseModel(200, "Bar", null, "SPEC", src)))),
                Map.of("Bar", schema("Bar", "id", "integer")));

        // Exactly ONE counted MAJOR. The 'id' SCHEMA_FIELD_EXTRA is LOW/MINOR (excluded); the coarse mismatch is
        // suppressed. Before the fix both were counted (coarse + field) — the collapse leaves a single MAJOR.
        assertThat(ReleaseVerdict.of(diff.diffCodeVsSpec(code, spec), new GateProperties()).major()).isEqualTo(1);
    }

    @Test
    void unresolvableSpecSchemaSuppressesResponseSchemaMismatch() {
        // spec response references a schema that isn't in components → can't structurally compare → suppress
        // (a bare name-compare here is the bug; the gap is surfaced as an extractor blind spot elsewhere).
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep("Wrapper", List.of(new ResponseModel(200, "Wrapper", null, "RETURN", src)))),
                Map.of("Wrapper", schema("Wrapper", "password", "string")));
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep("policies", List.of(new ResponseModel(200, "policies", null, "SPEC", src)))),
                Map.of());   // no schemas declared

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    private Endpoint epWithResponses(List<ResponseModel> responses) {
        return new Endpoint(HttpMethod.GET, "/x", "getX", List.of(), null, responses, null, null, List.of(), src);
    }

    @Test
    void adviceSourcedErrorStatusOmittedBySpecIsLowConfidence() {
        // a @ControllerAdvice handler (even for a specific exception) is GLOBAL — attached to every endpoint, not
        // provably reachable here → LOW (surfaced, not score-counted) so it can't flood the score.
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(epWithResponses(List.of(
                        new ResponseModel(200, null, null, "RETURN", src),
                        new ResponseModel(406, null, null, "EXCEPTION_HANDLER", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(epWithResponses(List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f -> f.getType() == FindingType.STATUS_CODE_MISSING
                && f.getSummary().contains("406") && f.getConfidence() == Confidence.LOW);
    }

    @Test
    void endpointReturnedErrorStatusOmittedBySpecIsMediumConfidence() {
        // the endpoint returns the error directly (ResponseEntity.badRequest()) → reachable here → MEDIUM
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(epWithResponses(List.of(
                        new ResponseModel(200, null, null, "RETURN", src),
                        new ResponseModel(400, null, null, "RESPONSE_ENTITY", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(epWithResponses(List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f -> f.getType() == FindingType.STATUS_CODE_MISSING
                && f.getSummary().contains("400") && f.getConfidence() == Confidence.MEDIUM);
    }

    private SchemaModel objRef(String name, String field, String refSchema) {
        ConstraintSet none = new ConstraintSet(null, null, null, null, null, null, null, null, null);
        return new SchemaModel(name, "object",
                List.of(new FieldModel(field, "object", null, false, none, refSchema, null)), null, src);
    }

    @Test
    void responseSchemaDivergenceTwoLevelsDeepIsFlagged() {
        // differently-named DTOs that diverge only at depth 2 (Leaf.v string vs LeafS.v integer) must still be caught
        Map<String, SchemaModel> codeSchemas = Map.of(
                "Root", objRef("Root", "child", "Child"),
                "Child", objRef("Child", "leaf", "Leaf"),
                "Leaf", schema("Leaf", "v", "string"));
        Map<String, SchemaModel> specSchemas = Map.of(
                "RootS", objRef("RootS", "child", "ChildS"),
                "ChildS", objRef("ChildS", "leaf", "LeafS"),
                "LeafS", schema("LeafS", "v", "integer"));
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(ep("Root", List.of(new ResponseModel(200, "Root", null, "RETURN", src)))), codeSchemas);
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(ep("RootS", List.of(new ResponseModel(200, "RootS", null, "SPEC", src)))), specSchemas);

        // The divergence is caught at the leaf as a precise type mismatch (Leaf.v string vs LeafS.v integer); the
        // coarse mismatch is suppressed because the field-level diff already describes the same defect (no double-count).
        Set<FindingType> types = diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType).collect(toSet());
        assertThat(types).contains(FindingType.SCHEMA_FIELD_TYPE_MISMATCH);
        assertThat(types).doesNotContain(FindingType.RESPONSE_SCHEMA_MISMATCH);
    }

    @Test
    void globalCatchAllErrorStatusIsFlaggedAtLowConfidence() {
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(epWithResponses(List.of(
                        new ResponseModel(200, null, null, "RETURN", src),
                        new ResponseModel(500, null, null, "EXCEPTION_HANDLER_GLOBAL", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(epWithResponses(List.of(new ResponseModel(200, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec)).anyMatch(f -> f.getType() == FindingType.STATUS_CODE_MISSING
                && f.getSummary().contains("500") && f.getConfidence() == ca.bnc.qe.veritas.finding.Confidence.LOW);
    }

    @Test
    void errorStatusDeclaredBySpecIsNotFlagged() {
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(epWithResponses(List.of(
                        new ResponseModel(200, null, null, "RETURN", src),
                        new ResponseModel(404, null, null, "EXCEPTION_HANDLER", src)))), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(epWithResponses(List.of(
                        new ResponseModel(200, null, null, "SPEC", src),
                        new ResponseModel(404, null, null, "SPEC", src)))), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec))
                .noneMatch(f -> f.getType() == FindingType.STATUS_CODE_MISSING && f.getSummary().contains("404"));
    }

    private ApiModel withEnumField(String specId, String origin, List<String> enumValues) {
        ConstraintSet c = new ConstraintSet(null, null, null, null, null, null, null, enumValues, null);
        SchemaModel s = new SchemaModel("Foo", "object",
                List.of(new FieldModel("status", "string", null, false, c, null, null)), null, src);
        return new ApiModel(specId, null, null, null,
                List.of(ep("Foo", List.of(new ResponseModel(200, "Foo", null, origin, src)))), Map.of("Foo", s));
    }

    private Endpoint epMedia(String origin, List<String> consumes, List<String> produces) {
        return new Endpoint(HttpMethod.POST, "/m", "m", List.of(), null,
                List.of(new ResponseModel(200, null, null, origin, src)), consumes, produces, List.of(), src);
    }

    @Test
    void producesMediaTypeDivergenceIsFlaggedButMatchingIsNot() {
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(epMedia("RETURN", List.of(), List.of("application/json"))), Map.of());
        ApiModel specXml = new ApiModel("repo-spec", null, null, null,
                List.of(epMedia("SPEC", List.of(), List.of("application/xml"))), Map.of());
        assertThat(diff.diffCodeVsSpec(code, specXml).stream().map(Finding::getType))
                .contains(FindingType.CONSUMES_PRODUCES_MISMATCH);

        // same base type, different charset param → NOT flagged
        ApiModel specJsonCharset = new ApiModel("repo-spec", null, null, null,
                List.of(epMedia("SPEC", List.of(), List.of("application/json;charset=UTF-8"))), Map.of());
        assertThat(diff.diffCodeVsSpec(code, specJsonCharset).stream().map(Finding::getType))
                .doesNotContain(FindingType.CONSUMES_PRODUCES_MISMATCH);
    }

    @Test
    void mediaTypeNotFlaggedWhenCodeDeclaresNone() {
        // code declares no media types (defaults to JSON) — comparing would be noise, so no finding
        ApiModel code = new ApiModel("code", null, null, null,
                List.of(epMedia("RETURN", List.of(), List.of())), Map.of());
        ApiModel spec = new ApiModel("repo-spec", null, null, null,
                List.of(epMedia("SPEC", List.of(), List.of("application/xml"))), Map.of());
        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.CONSUMES_PRODUCES_MISMATCH);
    }

    @Test
    void specSecuredButCodeCentralizesAuthIsNotFalselyFlagged() {
        Endpoint codeOpen = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of(), src);
        Endpoint specSecured = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of("bearerAuth"), src);
        // code model flags centralized security (SecurityFilterChain) via a blind spot
        ApiModel code = new ApiModel("code", null, null, null, List.of(codeOpen), Map.of(),
                List.of("Authorization appears centralized in a Spring Security configuration (SecurityFilterChain)."));
        ApiModel spec = new ApiModel("repo-spec", null, null, null, List.of(specSecured), Map.of());

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.SECURITY_MISMATCH);   // suppressed — annotation analysis can't see filter-chain authz
    }

    @Test
    void enumValueSetIgnoresOrderAndCase() {
        ApiModel code = withEnumField("code", "RETURN", List.of("ACTIVE", "CLOSED"));
        ApiModel spec = withEnumField("repo-spec", "SPEC", List.of("closed", "active"));   // same set, diff order+case

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .doesNotContain(FindingType.CONSTRAINT_GAP);
    }

    @Test
    void genuineEnumValueDifferenceIsStillFlagged() {
        ApiModel code = withEnumField("code", "RETURN", List.of("ACTIVE", "CLOSED"));
        ApiModel spec = withEnumField("repo-spec", "SPEC", List.of("ACTIVE", "PENDING"));   // genuinely different set

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .contains(FindingType.CONSTRAINT_GAP);
    }

    @Test
    void severityReflectsConsumerImpact() {
        // SECURITY_MISMATCH is CRITICAL (security-contract gap, OWASP API1/2/5)
        Endpoint secured = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "RETURN", src)), null, null, List.of("hasRole('ADMIN')"), src);
        Endpoint open = new Endpoint(HttpMethod.GET, "/s", "getS", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);
        assertThat(diff.diffCodeVsSpec(
                new ApiModel("code", null, null, null, List.of(secured), Map.of()),
                new ApiModel("repo-spec", null, null, null, List.of(open), Map.of())))
                .anyMatch(f -> f.getType() == FindingType.SECURITY_MISMATCH && f.getSeverity() == Severity.CRITICAL);

        // EXTRA_ENDPOINT (dead spec) is MINOR — documentation drift, doesn't break a running client
        Endpoint ghost = new Endpoint(HttpMethod.GET, "/ghost", "g", List.of(), null,
                List.of(new ResponseModel(200, null, null, "SPEC", src)), null, null, List.of(), src);
        assertThat(diff.diffCodeVsSpec(
                new ApiModel("code", null, null, null, List.of(), Map.of()),
                new ApiModel("repo-spec", null, null, null, List.of(ghost), Map.of())))
                .anyMatch(f -> f.getType() == FindingType.EXTRA_ENDPOINT && f.getSeverity() == Severity.MINOR);
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
