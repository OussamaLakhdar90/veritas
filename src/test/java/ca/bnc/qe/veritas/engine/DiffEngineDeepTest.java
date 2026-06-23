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

        assertThat(types).contains(
                FindingType.RESPONSE_SCHEMA_MISMATCH,   // code returns Foo{name}, spec declares Bar{id}
                FindingType.STATUS_CODE_EXTRA,          // spec documents 202, code never returns it
                FindingType.CONSTRAINT_GAP);            // maxLength 10 (code) vs 20 (spec)
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

        assertThat(diff.diffCodeVsSpec(code, spec).stream().map(Finding::getType))
                .contains(FindingType.RESPONSE_SCHEMA_MISMATCH);
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
