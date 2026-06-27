package ca.bnc.qe.veritas.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.persistence.TestCondition;
import ca.bnc.qe.veritas.persistence.TestConditionRepository;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.report.TestConditionListRenderer;
import ca.bnc.qe.veritas.testmgmt.TestAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TestConditionController.class)
class TestConditionControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private TestConditionRepository repository;
    @MockBean private TestStrategyRepository strategyRepository;
    @MockBean private TestAnalysisService service;
    @MockBean private TestConditionListRenderer renderer;

    private TestCondition condition(String id) {
        TestCondition c = new TestCondition();
        c.setId(id);
        c.setConditionRef("TCD-001");
        c.setServiceName("svc");
        c.setAutomation("CANDIDATE");
        c.setStatus("PROPOSED");
        return c;
    }

    @Test
    void listsConditionsForAService() throws Exception {
        when(repository.findByServiceNameOrderByCreatedAtDesc("svc")).thenReturn(List.of(condition("c1")));
        mvc.perform(get("/api/v1/services/svc/test-conditions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conditionRef").value("TCD-001"));
    }

    @Test
    void getIsNotFoundForAnUnknownId() throws Exception {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/test-conditions/nope")).andExpect(status().isNotFound());
    }

    @Test
    void generateReturns202() throws Exception {
        when(service.analyze(eq("svc"), any(), any(), any())).thenReturn(List.of(condition("c1")));
        mvc.perform(post("/api/v1/services/svc/test-conditions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"some basis\",\"owner\":\"api\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].id").value("c1"));
    }

    @Test
    void patchUpdatesTheAutomationDecision() throws Exception {
        TestCondition c = condition("c1");
        when(repository.findById("c1")).thenReturn(Optional.of(c));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(patch("/api/v1/test-conditions/c1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"automation\":\"automated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.automation").value("AUTOMATED"));
    }

    @Test
    void patchRejectsAnUnknownAutomationValueWith400() throws Exception {
        when(repository.findById("c1")).thenReturn(Optional.of(condition("c1")));
        mvc.perform(patch("/api/v1/test-conditions/c1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"automation\":\"sometimes\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportRendersHtmlForAKnownStrategy() throws Exception {
        TestStrategy s = new TestStrategy();
        s.setId("strat-1");
        when(strategyRepository.findById("strat-1")).thenReturn(Optional.of(s));
        when(repository.findByTestStrategyIdOrderByConditionRefAsc("strat-1")).thenReturn(List.of(condition("c1")));
        when(renderer.renderHtml(eq(s), any())).thenReturn("<html><body>condition list</body></html>");

        mvc.perform(get("/api/v1/strategies/strat-1/test-conditions/report"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("condition list")));
    }

    @Test
    void routingSplitsConditionsByAutomationCandidacy() throws Exception {
        TestCondition auto = condition("a"); auto.setConditionRef("TCD-001"); auto.setAutomation("AUTOMATED");
        TestCondition man = condition("m"); man.setConditionRef("TCD-002"); man.setAutomation("MANUAL");
        TestCondition cand = condition("c"); cand.setConditionRef("TCD-003"); cand.setAutomation(null);
        when(repository.findByTestStrategyIdOrderByConditionRefAsc("s1"))
                .thenReturn(List.of(auto, man, cand));
        mvc.perform(get("/api/v1/strategies/s1/test-conditions/routing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.automated[0]").value("TCD-001"))
                .andExpect(jsonPath("$.manual[0]").value("TCD-002"))
                .andExpect(jsonPath("$.candidate[0]").value("TCD-003"));
    }

    @Test
    void reportIs404ForAnUnknownStrategy() throws Exception {
        when(strategyRepository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/strategies/nope/test-conditions/report")).andExpect(status().isNotFound());
    }
}
