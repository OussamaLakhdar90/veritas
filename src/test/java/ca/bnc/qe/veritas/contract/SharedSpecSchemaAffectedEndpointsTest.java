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

/**
 * S13j-3: a spec-keyed root-cause merge can pull a components-loop finding (whose endpoint is a SCHEMA LOCUS like
 * "Order.total", not an HTTP route) together with a per-endpoint one that shares the same spec locus. The survivor's
 * affectedEndpoints must NOT list the schema locus as if it were an endpoint (and must not make a false "2 endpoints"
 * claim). Scoring is unaffected either way — the duplicate is still dropped and the defect is charged once.
 */
class SharedSpecSchemaAffectedEndpointsTest {

    /** A schema-field finding sharing the SAME spec locus, with an explicit endpoint + code evidence file. */
    private Finding finding(String endpoint, String file) {
        return Finding.builder().findingId(endpoint).type(FindingType.SCHEMA_FIELD_MISSING).layer(Layer.L4)
                .severity(Severity.MAJOR).confidence(Confidence.HIGH).origin("DETERMINISTIC")
                .endpoint(endpoint).specSource("code-vs-spec").specLocus("Order#total")
                .codeEvidence(SourceRef.code(file, 10, 10, null))
                .summary("Field 'total' of Order is in code but missing from the spec schema").build();
    }

    @Test
    void nonHttpSchemaLocusIsNotAccruedAsAnAffectedEndpoint() {
        // The same-named code DTO 'Order' vs spec schema 'Order' (lacking 'total') → the components loop flags it with
        // endpoint "Order.total" (a schema locus). A differently-named wrapper 'OrderDto' also carries 'total' and
        // $refs the same spec schema → a per-endpoint finding on "GET /orders". Both share spec locus Order#total.
        Finding componentsLoop = finding("Order.total", "Order.java");
        Finding perEndpoint = finding("GET /orders", "OrderDto.java");

        List<Finding> collapsed = ContractValidationService.collapseByRootCause(List.of(componentsLoop, perEndpoint));

        List<Finding> missing = collapsed.stream()
                .filter(f -> f.getType() == FindingType.SCHEMA_FIELD_MISSING).toList();
        assertThat(missing).hasSize(1);   // the duplicate is still collapsed away — one charge
        Finding survivor = missing.get(0);
        // The survivor's affectedEndpoints must contain NO entry lacking an HTTP-method prefix (pre-fix it listed the
        // schema locus "Order.total" and falsely claimed 2 endpoints). Asserted on the RAW content so it fails on
        // pre-fix code regardless of the isHttpEndpoint helper existing.
        assertThat(survivor.getAffectedEndpoints())
                .doesNotContain("Order.total")
                .allSatisfy(ep -> assertThat(ep).matches("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)\\s.*"));
    }
}
