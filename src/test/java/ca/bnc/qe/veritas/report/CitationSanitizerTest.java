package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** §0.1 enforcement: strip leaked syllabus section numbers from citations, keep the named concept. */
class CitationSanitizerTest {

    private final CitationSanitizer sanitizer = new CitationSanitizer();
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void stripsSectionMarkersAndTrailingDottedRefsButKeepsTheConcept() {
        assertThat(sanitizer.strip("CTAL-TM — Risk-Based Testing §1.3.3")).isEqualTo("CTAL-TM — Risk-Based Testing");
        assertThat(sanitizer.strip("CTFL — Boundary Value Analysis (§4.2.1)")).isEqualTo("CTFL — Boundary Value Analysis");
        assertThat(sanitizer.strip("CTAL-TM — Exit Criteria 1.4.3")).isEqualTo("CTAL-TM — Exit Criteria");
    }

    @Test
    void leavesACleanNamedConceptUntouched() {
        assertThat(sanitizer.strip("CTAL-TM — Risk-Based Testing")).isEqualTo("CTAL-TM — Risk-Based Testing");
        assertThat(sanitizer.strip("CTAL-TA 3")).isEqualTo("CTAL-TA 3");   // bare number (no dot) is kept
        assertThat(sanitizer.strip(null)).isNull();
    }

    @Test
    void sanitizesTheCitationFieldsOfADeliverableInPlace() throws Exception {
        ObjectNode d = (ObjectNode) om.readTree("{"
                + "\"riskRegister\":[{\"id\":\"R1\",\"citation\":\"CTAL-TM — Risk-Based Testing §1.3.3\"}],"
                + "\"testApproach\":{\"techniques\":[{\"name\":\"BVA\",\"citation\":\"CTFL — BVA §4.2\"}]},"
                + "\"exitCriteria\":[{\"criterion\":\"x\",\"citation\":\"CTAL-TM — Exit Criteria §1.4.3\"}]}");

        sanitizer.sanitizeDeliverable(d);

        assertThat(d.path("riskRegister").get(0).path("citation").asText()).isEqualTo("CTAL-TM — Risk-Based Testing");
        assertThat(d.path("testApproach").path("techniques").get(0).path("citation").asText()).isEqualTo("CTFL — BVA");
        assertThat(d.path("exitCriteria").get(0).path("citation").asText()).isEqualTo("CTAL-TM — Exit Criteria");
    }
}
