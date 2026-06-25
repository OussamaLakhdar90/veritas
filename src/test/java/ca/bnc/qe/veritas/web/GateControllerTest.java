package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.persistence.GateDecision;
import ca.bnc.qe.veritas.persistence.GateDecisionRepository;
import ca.bnc.qe.veritas.skill.ConflictException;
import ca.bnc.qe.veritas.skill.GateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The human-approval gate API: list pending, approve/reject, and a 409 for an already-decided gate. */
@WebMvcTest(GateController.class)
class GateControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private GateService gateService;
    @MockBean private GateDecisionRepository repository;

    private static GateDecision gate(String id, String status) {
        GateDecision g = new GateDecision();
        g.setId(id);
        g.setStatus(status);
        return g;
    }

    @Test
    void listsPendingGatesByDefault() throws Exception {
        when(repository.findByStatusOrderByCreatedAtDesc("PENDING")).thenReturn(List.of(gate("g1", "PENDING")));
        mvc.perform(get("/api/v1/gates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("g1"));
    }

    @Test
    void approveDelegatesToTheService() throws Exception {
        when(gateService.approve("g1", "alice")).thenReturn(gate("g1", "APPROVED"));
        mvc.perform(post("/api/v1/gates/g1/approve").contentType("application/json").content("{\"approver\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void decidingAnAlreadyDecidedGateIs409() throws Exception {
        when(gateService.approve(eq("g1"), any())).thenThrow(new ConflictException("gate already decided"));
        mvc.perform(post("/api/v1/gates/g1/approve").contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }
}
