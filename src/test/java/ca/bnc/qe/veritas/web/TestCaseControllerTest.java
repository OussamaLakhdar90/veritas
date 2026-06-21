package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import ca.bnc.qe.veritas.persistence.TestCase;
import ca.bnc.qe.veritas.persistence.TestCaseRepository;
import ca.bnc.qe.veritas.testmgmt.CreateTestCasesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test: PATCH /test-cases/{id} approves a proposed case (the RTM-workspace per-row action). */
@WebMvcTest(TestCaseController.class)
class TestCaseControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private TestCaseRepository repository;
    @MockBean private CreateTestCasesService service;

    @Test
    void patchApprovesCaseAndStampsApprover() throws Exception {
        TestCase tc = new TestCase();
        tc.setStatus("PROPOSED");
        when(repository.findById("tc1")).thenReturn(Optional.of(tc));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(patch("/api/v1/test-cases/tc1").contentType("application/json")
                        .content("{\"status\":\"APPROVED\",\"actor\":\"qa-lead\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("qa-lead"));
    }
}
