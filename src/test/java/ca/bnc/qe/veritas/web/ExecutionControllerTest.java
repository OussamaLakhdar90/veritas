package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.execution.ExecutionService;
import ca.bnc.qe.veritas.execution.ExecutionSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /execution/completion returns the completion summary for a JQL's tests. */
@WebMvcTest(ExecutionController.class)
class ExecutionControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ExecutionService execution;

    @Test
    void returnsTheCompletionSummary() throws Exception {
        when(execution.completion(eq("ciam"), eq("project = CIAM"), eq("api"))).thenReturn(
                new ExecutionSummary("ciam", "project = CIAM", 3, 2, 1, 0, 1, List.of(), "verdict"));

        mvc.perform(get("/api/v1/execution/completion").param("jql", "project = CIAM").param("service", "ciam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.passed").value(2))
                .andExpect(jsonPath("$.failed").value(1));
    }
}
