package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

/** Fix 5: the cross-list dedup key must not collapse findings from different specs or case-distinct param names. */
class CrossListDedupTest {

    private Finding f(FindingType t, String endpoint, String specSource, String summary) {
        return Finding.builder().findingId("x").type(t).layer(Layer.L4).severity(Severity.MAJOR)
                .confidence(Confidence.MEDIUM).origin("DETERMINISTIC").endpoint(endpoint)
                .specSource(specSource).summary(summary).build();
    }

    @Test
    void sameFindingFromDifferentSpecsGetsDistinctKeys() {
        String k1 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it"));
        String k2 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "confluence-spec",
                "Code can return 500 but the spec doesn't document it"));
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void caseDistinctParameterFindingsGetDistinctKeys() {
        String k1 = ContractValidationService.dedupKey(f(FindingType.PARAM_TYPE_MISMATCH, "GET /x", "repo-spec",
                "Parameter 'X' type — code string vs spec integer"));
        String k2 = ContractValidationService.dedupKey(f(FindingType.PARAM_TYPE_MISMATCH, "GET /x", "repo-spec",
                "Parameter 'x' type — code string vs spec integer"));
        assertThat(k1).isNotEqualTo(k2);
    }

    private Finding fieldFinding(String endpoint, String file, int line) {
        return Finding.builder().findingId(endpoint).type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC").endpoint(endpoint)
                .specSource("code-vs-spec").summary("Field 'excludeAttributes' of " + endpoint + " is in code but missing")
                .codeEvidence(SourceRef.code(file, line, line, null)).build();
    }

    @Test
    void sameSharedDtoFieldAcrossEndpointsCollapsesToOneFindingWithAffectedEndpoints() {
        Finding a = fieldFinding("GET /ciam/policies", "PasswordComplexity.java", 42);
        Finding b = fieldFinding("GET /ciam/policies/{app}", "PasswordComplexity.java", 42);   // same field/line
        Finding unrelated = f(FindingType.PARAM_TYPE_MISMATCH, "GET /y", "code-vs-spec", "Parameter 'q' type differs");

        List<Finding> out = ContractValidationService.collapseByCodeLocus(List.of(a, b, unrelated));

        assertThat(out).hasSize(2);   // the two field findings became one; the unrelated one is untouched
        Finding merged = out.get(0);
        assertThat(merged.getType()).isEqualTo(FindingType.SCHEMA_FIELD_MISSING);
        assertThat(merged.getAffectedEndpoints())
                .containsExactly("GET /ciam/policies", "GET /ciam/policies/{app}");
    }

    @Test
    void findingsWithoutCodeEvidenceAreNeverCollapsed() {
        Finding a = f(FindingType.PARAM_MISSING, "GET /a", "code-vs-spec", "Param 'x' documented but ignored");
        Finding b = f(FindingType.PARAM_MISSING, "GET /b", "code-vs-spec", "Param 'x' documented but ignored");
        List<Finding> out = ContractValidationService.collapseByCodeLocus(List.of(a, b));
        assertThat(out).hasSize(2);   // no code anchor → never merged across endpoints
    }

    @Test
    void exactDuplicatesShareAKeyIgnoringTrailingPunctuation() {
        String k1 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it"));
        String k2 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it."));
        assertThat(k1).isEqualTo(k2);
    }
}
