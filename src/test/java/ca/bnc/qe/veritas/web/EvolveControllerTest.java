package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.evolve.ClassificationTrain;
import ca.bnc.qe.veritas.evolve.DisputeCluster;
import ca.bnc.qe.veritas.evolve.DisputeClusterService;
import ca.bnc.qe.veritas.evolve.EngineEvolutionService;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
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
    @MockBean private FindingRecordRepository findingRepository;
    @MockBean private DisputeClusterService disputeClusterService;

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

    @Test
    void debtReportsUnspecifiedAndDisputedCounts() throws Exception {
        when(findingRepository.countDistinctUnspecified()).thenReturn(4L);
        when(findingRepository.countDistinctDisputed()).thenReturn(2L);
        mvc.perform(get("/api/v1/engine-evolution/debt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unspecified").value(4))
                .andExpect(jsonPath("$.disputed").value(2));
    }

    @Test
    void disputesRollsUpTheDisputedFindingsByType() throws Exception {
        when(disputeClusterService.computeClusters()).thenReturn(List.of(new DisputeCluster(
                FindingType.PARAM_TYPE_MISMATCH, 3, 2, Map.of("NEEDS_DETECTION_FIX", 1),
                List.of(new DisputeCluster.Example("f1", "s1", "svc-a", "GET /a", "type mismatch",
                        "int32 vs integer", "NEEDS_DETECTION_FIX")))));
        mvc.perform(get("/api/v1/engine-evolution/disputes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].findingType").value("PARAM_TYPE_MISMATCH"))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[0].verdictBreakdown.NEEDS_DETECTION_FIX").value(1))
                .andExpect(jsonPath("$[0].examples[0].scanId").value("s1"));
    }

    @Test
    void dismissRoutesToTheService() throws Exception {
        when(service.dismiss(any(), any())).thenReturn(train());
        mvc.perform(post("/api/v1/engine-evolution/proposals/t1/dismiss")
                        .contentType("application/json").content("{\"reason\":\"too contentious\"}"))
                .andExpect(status().isOk());
    }
}
