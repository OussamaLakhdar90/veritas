package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bnc.qe.veritas.persistence.TestStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** The evidence why-doc: features + their cited evidence, gaps, and the scorecard — and it escapes + tolerates gaps. */
class WhyDocRendererTest {

    private final ObjectMapper m = new ObjectMapper();
    private final WhyDocRenderer renderer = new WhyDocRenderer(m);

    private TestStrategy strategy(String deliverableJson, String scorecardJson) {
        TestStrategy s = new TestStrategy();
        s.setServiceName("ciam-policies");
        s.setVersion(1);
        s.setDeliverableJson(deliverableJson);
        s.setScorecardJson(scorecardJson);
        return s;
    }

    @Test
    void rendersFeaturesTheirCitedEvidenceGapsAndTheScorecard() {
        ObjectNode d = m.createObjectNode();
        d.put("summary", "Multi-source test strategy for ciam-policies.");
        ObjectNode risk = d.putArray("riskRegister").addObject();
        risk.put("featureId", "f1").put("feature", "Get policy").put("featureStatus", "IMPLEMENTED");
        risk.putArray("evidence").addObject().put("unitId", "JIRA-1").put("quote", "returns the policy").put("gloss", "the happy path");
        risk.put("content", "Validate the 200 response and authorization.");
        ObjectNode approach = d.putArray("testApproach").addObject();
        approach.put("featureId", "f1").put("feature", "Get policy").put("featureStatus", "IMPLEMENTED");
        approach.putArray("evidence").addObject().put("unitId", "CODE:PolicyController#GET /policies").put("quote", "GET /policies");
        approach.put("content", "Security + functional levels.");
        d.putArray("gaps").addObject().put("kind", "COVERAGE_GAP").put("feature", "f2")
                .put("message", "\"Delete policy\" is marked done in Jira but no code was found.");

        String sc = "{\"verdict\":\"DEGRADED\",\"confidence\":80,\"checks\":["
                + "{\"name\":\"All sections grounded in evidence\",\"passed\":false,\"detail\":\"1 section(s) dropped.\"}]}";

        String html = renderer.renderHtml(strategy(d.toString(), sc));

        assertThat(html)
                .contains("Get policy")                           // feature name
                .contains("Implemented")                          // status label
                .contains("Risk register").contains("Test approach")
                .contains("JIRA-1").contains("returns the policy").contains("the happy path")   // cited evidence
                .contains("CODE:PolicyController#GET /policies")
                .contains("DEGRADED").contains("80%")             // scorecard verdict + confidence
                .contains("1 section(s) dropped")                 // a failing check's detail
                .contains("Done in Jira, not built");             // the COVERAGE_GAP gap label
    }

    @Test
    void escapesHtmlInEvidenceAndContent() {
        ObjectNode d = m.createObjectNode();
        ArrayNode risk = d.putArray("riskRegister");
        ObjectNode r = risk.addObject();
        r.put("featureId", "f1").put("feature", "<x>").put("featureStatus", "IMPLEMENTED");
        r.putArray("evidence").addObject().put("unitId", "u1").put("quote", "<script>alert(1)</script>").put("gloss", "");
        r.put("content", "a & b < c");

        String html = renderer.renderHtml(strategy(d.toString(), null));

        assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(html).contains("a &amp; b &lt; c");
    }

    @Test
    void toleratesAnEmptyOrNullDeliverable() {
        String html = renderer.renderHtml(strategy(null, null));
        assertThat(html).contains("Strategy Evidence").contains("ciam-policies");
        assertThat(html).doesNotContain(">null<");   // no literal null leaks into the body
    }
}
