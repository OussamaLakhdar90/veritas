package ca.bnc.qe.veritas.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.persistence.CostEntry;
import ca.bnc.qe.veritas.persistence.CostEntryRepository;
import ca.bnc.qe.veritas.persistence.RunStepRepository;
import ca.bnc.qe.veritas.persistence.SkillRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The read-only cost API: the per-action ledger, an aggregate summary, and 404 for an unknown run. */
@WebMvcTest(CostController.class)
class CostControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private SkillRunRepository runs;
    @MockBean private RunStepRepository steps;
    @MockBean private CostEntryRepository costs;

    private static CostEntry entry(String skill, double cost) {
        CostEntry e = new CostEntry();
        e.setSkill(skill);
        e.setEstCostUsd(cost);
        return e;
    }

    @Test
    void returnsTheCostLedger() throws Exception {
        when(costs.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(entry("validate-contract", 0.40)));
        mvc.perform(get("/api/v1/costs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill").value("validate-contract"));
    }

    @Test
    void summarizesSpendTotalAndBySkill() throws Exception {
        when(costs.findAll()).thenReturn(List.of(
                entry("validate-contract", 0.40), entry("validate-contract", 0.10), entry("test-strategy", 0.25)));
        mvc.perform(get("/api/v1/costs/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").value(3))
                .andExpect(jsonPath("$.totalEstCostUsd").value(0.75))
                .andExpect(jsonPath("$.bySkill.['validate-contract']").value(0.5));
    }

    @Test
    void unknownRunIs404() throws Exception {
        when(runs.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/runs/nope")).andExpect(status().isNotFound());
    }

    @Test
    void costTrendZeroFillsTheWindowAndCountsTodaysSpend() throws Exception {
        CostEntry e = entry("validate-contract", 1.25);
        e.setCreatedAt(java.time.Instant.now());   // falls in today's bucket
        when(costs.findAll()).thenReturn(List.of(e));
        mvc.perform(get("/api/v1/costs/trend?days=7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))                 // zero-filled to the window
                .andExpect(jsonPath("$[6].actions").value(1))               // most recent day = today
                .andExpect(jsonPath("$[6].totalUsd").value(1.25))
                .andExpect(jsonPath("$[0].actions").value(0));              // older days empty
    }
}
