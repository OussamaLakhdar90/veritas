package ca.bnc.qe.veritas.evidence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/** Code model → ENDPOINT / DTO_CONSTRAINT / GLOBAL_CAVEAT units, with unique ids, path hints, and redaction. */
class CodeEvidenceAdapterTest {

    private final CodeEvidenceAdapter adapter = new CodeEvidenceAdapter();

    private ApiModel ciamPoliciesModel() {
        SourceRef src = SourceRef.code("src/main/java/ca/bnc/PolicyController.java", 30, 35, "return repo.get(appId);");
        ParamModel appId = new ParamModel("appId", ParamLocation.PATH, "string", null, true, null, src);
        ResponseModel ok = new ResponseModel(200, "Policy", List.of("application/json"), "RETURN", src);
        ResponseModel notFound = new ResponseModel(404, null, List.of(), "EXCEPTION_HANDLER", src);
        Endpoint ep = new Endpoint(HttpMethod.GET, "/policies/{appId}", "getPolicy", List.of(appId), null,
                List.of(ok, notFound), List.of(), List.of("application/json"), List.of("ROLE_ADMIN"), src);

        FieldModel minLength = new FieldModel("minLength", "integer", null, true, null, null, src);
        SchemaModel schema = new SchemaModel("PasswordPolicy", "object", List.of(minLength), List.of(), src);

        // A blind spot containing an email exercises the redaction path.
        return new ApiModel("code", "ciam-policies", "1", null, List.of(ep),
                Map.of("PasswordPolicy", schema),
                List.of("Authorization centralized in SecurityFilterChain; contact admin@bnc.ca for the rules."));
    }

    @Test
    void buildsEndpointDtoAndCaveatUnitsWithUniqueDerivableIds() {
        SourceExtraction r = adapter.extract(ciamPoliciesModel());

        EvidenceUnit endpoint = byType(r, UnitType.ENDPOINT);
        assertThat(endpoint.id()).isEqualTo("CODE:PolicyController#GET /policies/{appId}");
        assertThat(endpoint.source()).isEqualTo(SourceKind.CODE);
        assertThat(endpoint.text())
                .contains("GET /policies/{appId}")
                .contains("appId (PATH, required)")
                .contains("200 Policy").contains("404")
                .contains("secured: ROLE_ADMIN");
        assertThat(endpoint.hints()).contains("policies", "policycontroller");   // path-noun + controller class
        assertThat(endpoint.link()).isEqualTo("src/main/java/ca/bnc/PolicyController.java:30");

        EvidenceUnit dto = byType(r, UnitType.DTO_CONSTRAINT);
        assertThat(dto.id()).isEqualTo("CODE:PasswordPolicy.schema");
        assertThat(dto.text()).contains("PasswordPolicy").contains("minLength (integer, required)");

        EvidenceUnit caveat = byType(r, UnitType.GLOBAL_CAVEAT);
        assertThat(caveat.hints()).containsExactly("cross-cutting");
        assertThat(caveat.text()).contains("[REDACTED-EMAIL]").doesNotContain("admin@bnc.ca");

        assertThat(r.redactions()).isEqualTo(1);
        assertThat(r.requested()).isEqualTo(1);
        assertThat(r.fetched()).isEqualTo(1);
    }

    @Test
    void nullModelYieldsAnEmptyNotFetchedExtraction() {
        SourceExtraction r = adapter.extract(null);
        assertThat(r.units()).isEmpty();
        assertThat(r.requested()).isZero();
        assertThat(r.fetched()).isZero();
    }

    @Test
    void emptyButNonNullModelFetchesNothingSoItIsDroppedFromTheMix() {
        // A parsed repo with zero Spring controllers/schemas → 0 units; §1.3: fetched=0 (requested=1) so the
        // realised SourceMix won't claim code=true and a code-only run over it hard-fails before spend.
        ApiModel empty = new ApiModel("code", "svc", "1", null, List.of(), Map.of());
        SourceExtraction r = adapter.extract(empty);
        assertThat(r.units()).isEmpty();
        assertThat(r.requested()).isEqualTo(1);
        assertThat(r.fetched()).isZero();
    }

    @Test
    void classNameIsDerivedFromTheSourceFileBasename() {
        assertThat(CodeEvidenceAdapter.classFromSource(
                SourceRef.code("a/b/AuthController.java", 1, 1, null))).isEqualTo("AuthController");
        assertThat(CodeEvidenceAdapter.classFromSource(SourceRef.code("Foo.java", 1, 1, null))).isEqualTo("Foo");
        assertThat(CodeEvidenceAdapter.classFromSource(null)).isEqualTo("Code");
    }

    private static EvidenceUnit byType(SourceExtraction r, UnitType type) {
        return r.units().stream().filter(u -> u.type() == type).findFirst().orElseThrow();
    }
}
