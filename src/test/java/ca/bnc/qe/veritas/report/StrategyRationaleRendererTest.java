package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.persistence.TestStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The rationale doc gives full explanatory sections (Principle + Why + How), grounded in the glossary. */
class StrategyRationaleRendererTest {

    private final StrategyRationaleRenderer renderer = new StrategyRationaleRenderer(new ObjectMapper());

    @Test
    void rendersExplanatorySectionsFromTheStrategy() {
        TestStrategy s = new TestStrategy();
        s.setServiceName("ciam-policies");
        s.setVersion(2);
        s.setDeliverableJson("""
                {"scope":{"objectives":["Validate authZ"]},
                 "riskRegister":[{"id":"R1","description":"Unauthorized read","level":"HIGH","mitigation":"AuthZ matrix"}],
                 "testApproach":{"levels":["System"],"types":["Security"],
                   "techniques":[{"name":"Decision Table","rationale":"role x state rules","riskId":"R1",
                                  "citation":"CTAL-TA — Decision Table Testing"}]},
                 "exitCriteria":[{"criterion":"No open critical defects","metric":"open critical = 0"}]}
                """);

        String html = renderer.renderHtml(s);

        // Each section carries an explained Principle (from the glossary), not just a citation.
        assertThat(html).contains("Principle.");
        assertThat(html).contains("Risk-based prioritization").contains("proportion to product and project risk");
        assertThat(html).contains("R1").contains("AuthZ matrix");                 // how it serves
        assertThat(html).contains("Decision Table").contains("Combinations of conditions"); // technique principle
        assertThat(html).contains("role x state rules");                          // why here (rationale)
        assertThat(html).contains("Exit criteria").contains("No open critical defects");
    }
}
