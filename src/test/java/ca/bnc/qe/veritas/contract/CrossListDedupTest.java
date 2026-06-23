package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void exactDuplicatesShareAKeyIgnoringTrailingPunctuation() {
        String k1 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it"));
        String k2 = ContractValidationService.dedupKey(f(FindingType.STATUS_CODE_MISSING, "GET /x", "repo-spec",
                "Code can return 500 but the spec doesn't document it."));
        assertThat(k1).isEqualTo(k2);
    }
}
