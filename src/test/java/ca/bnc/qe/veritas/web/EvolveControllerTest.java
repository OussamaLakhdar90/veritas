package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.evolve.ClassificationTrain;
import ca.bnc.qe.veritas.evolve.EngineEvolutionService;
import ca.bnc.qe.veritas.settings.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EvolveController.class)
class EvolveControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private EngineEvolutionService service;
    @MockBean private CurrentUser currentUser;

    private static ClassificationTrain train() {
        ClassificationTrain t = new ClassificationTrain();
        t.setFindingType("STATUS_CODE_MISSING");
        t.setFinalSeverity("MAJOR");
        t.setStatus("PROPOSED");
        return t;
    }

    @Test
    void listsProposals() throws Exception {
        when(service.all()).thenReturn(List.of(train()));
        mvc.perform(get("/api/v1/engine-evolution/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].findingType").value("STATUS_CODE_MISSING"));
    }

    @Test
    void refreshRecomputesProposals() throws Exception {
        when(currentUser.principalId()).thenReturn("alice");
        when(service.refresh("alice")).thenReturn(List.of(train()));
        mvc.perform(post("/api/v1/engine-evolution/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PROPOSED"));
    }

    @Test
    void challengeRoutesTheOverride() throws Exception {
        when(currentUser.principalId()).thenReturn("alice");
        when(service.challenge(any(), any(), any())).thenReturn(train());
        mvc.perform(post("/api/v1/engine-evolution/proposals/t1/challenge")
                        .contentType("application/json")
                        .content("{\"severity\":\"CRITICAL\",\"comment\":\"breaks consumers\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void openPrRoutesToTheService() throws Exception {
        when(currentUser.principalId()).thenReturn("alice");
        when(service.openPr("t1", "alice")).thenReturn(train());
        mvc.perform(post("/api/v1/engine-evolution/proposals/t1/open-pr"))
                .andExpect(status().isOk());
    }

    @Test
    void markMergedRoutesToTheService() throws Exception {
        when(service.markMerged("t1")).thenReturn(train());
        mvc.perform(post("/api/v1/engine-evolution/proposals/t1/mark-merged"))
                .andExpect(status().isOk());
    }
}
