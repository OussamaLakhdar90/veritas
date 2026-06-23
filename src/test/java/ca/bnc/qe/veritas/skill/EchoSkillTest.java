package ca.bnc.qe.veritas.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** End-to-end Phase-0 verification: the echo skill runs through the framework using the mock LLM gateway. */
@SpringBootTest
class EchoSkillTest {

    @Autowired
    private SkillRunner skillRunner;

    @Autowired
    private CostEntryRepository costEntries;

    @Test
    void runsEchoSkillEndToEnd() {
        SkillRunResult result = skillRunner.run("echo", Map.of("text", "hello veritas"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.outputs()).containsKeys("upper", "echo");
        assertThat(result.outputs().get("upper")).isEqualTo("HELLO VERITAS");

        JsonNode echo = (JsonNode) result.outputs().get("echo");
        assertThat(echo.get("message").asText()).isEqualTo("echo ok");
    }

    /**
     * The LLM step of a skill run must land in the cost ledger ({@link CostEntryRepository}) — the table the
     * /api/v1/costs API reads — not only in the per-run RunStep rows. Previously SkillRunner billed to RunStep
     * only, so any LLM action run through the generic pipeline was invisible to billing.
     */
    @Test
    void skillRunLlmStepIsRecordedInTheCostLedger() {
        skillRunner.run("echo", Map.of("text", "ledger check"));

        assertThat(costEntries.findAll())
                .anyMatch(e -> "echo".equals(e.getSkill()) && "echo".equals(e.getAction()));
    }
}
