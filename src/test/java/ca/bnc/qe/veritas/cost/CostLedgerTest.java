package ca.bnc.qe.veritas.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.contract.ContractValidationService;
import ca.bnc.qe.veritas.contract.SpecInput;
import ca.bnc.qe.veritas.contract.ValidationRequest;
import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Proves the real (non-echo) skills feed the per-action cost ledger — the explicit "cost per LLM action". */
@SpringBootTest
class CostLedgerTest {

    @Autowired private ContractValidationService contract;
    @Autowired private CostEntryRepository costs;

    @Test
    void validateContractWritesACostEntryPerLlmAction() throws Exception {
        Path repo = Path.of(getClass().getClassLoader().getResource("fixtures/policies").toURI());
        String spec = Files.readString(
                Path.of(getClass().getClassLoader().getResource("fixtures/policies-spec.yaml").toURI()));

        contract.validate(new ValidationRequest("ciam-policies", null, null, null, repo,
                List.of(new SpecInput("repo-spec", spec)), true, "tester"));   // llmEnabled=true → reconcile runs

        List<CostEntry> entries = costs.findBySkillOrderByCreatedAtDesc("validate-contract");
        assertThat(entries).isNotEmpty();
        CostEntry latest = entries.get(0);
        assertThat(latest.getAction()).isEqualTo("reconcile");
        assertThat(latest.getModel()).isNotBlank();
        assertThat(latest.getOwner()).isEqualTo("tester");
        assertThat(latest.getEstCostUsd()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void recorderPersistsLedgerRow(@Autowired CostRecorder recorder) {
        long before = costs.findBySkillOrderByCreatedAtDesc("unit-skill").size();
        CostResult r = recorder.record("unit-skill", "act", "claude-sonnet-4", "a prompt", "a response", "me");
        assertThat(r.estCostUsd()).isGreaterThanOrEqualTo(0.0);
        assertThat(costs.findBySkillOrderByCreatedAtDesc("unit-skill")).hasSize((int) before + 1);
    }
}
