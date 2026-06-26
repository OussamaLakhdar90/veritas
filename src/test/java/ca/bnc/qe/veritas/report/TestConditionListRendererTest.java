package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import org.junit.jupiter.api.Test;

/** The Test Condition List document renders the rows, traces to its strategy, and handles the empty case. */
class TestConditionListRendererTest {

    private final TestConditionListRenderer renderer = new TestConditionListRenderer();

    private TestCondition condition(String ref, String desc, String automation) {
        TestCondition c = new TestCondition();
        c.setConditionRef(ref);
        c.setDescription(desc);
        c.setSourceBasisItem("POST /policies");
        c.setPriority("P1");
        c.setRiskRef("R1");
        c.setQualityCharacteristic("Functional suitability");
        c.setTechnique("Boundary Value Analysis");
        c.setAutomation(automation);
        c.setStatus("PROPOSED");
        return c;
    }

    @Test
    void rendersTheConditionListWithTraceabilityAndAutomationBadges() {
        TestStrategy s = new TestStrategy();
        s.setServiceName("ciam-policies");
        s.setVersion(2);

        String html = renderer.renderHtml(s, List.of(
                condition("TCD-001", "Create policy rejects invalid payloads", "AUTOMATED"),
                condition("TCD-002", "Exploratory clarity check", "MANUAL")));

        assertThat(html)
                .contains("Test Condition List")
                .contains("ciam-policies")
                .contains("derived from strategy v2")
                .contains("TCD-001")
                .contains("Create policy rejects invalid payloads")
                .contains("AUTOMATED")
                .contains("MANUAL")
                .contains("Source (basis item)");
    }

    @Test
    void escapesDynamicContent() {
        TestStrategy s = new TestStrategy();
        s.setServiceName("svc");
        String html = renderer.renderHtml(s, List.of(condition("TCD-001", "<script>alert(1)</script>", "AUTOMATED")));
        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
    }

    @Test
    void showsAnEmptyStateWhenNoConditions() {
        TestStrategy s = new TestStrategy();
        s.setServiceName("svc");
        assertThat(renderer.renderHtml(s, List.of())).contains("No test conditions");
    }
}
