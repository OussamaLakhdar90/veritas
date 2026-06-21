package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.bnc.qe.veritas.codegen.CodegenService;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test for the implement-tests trigger (the #2 audit blocker): POST returns 202 + run. */
@WebMvcTest(CodegenController.class)
class CodegenControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private CodegenRunRepository runs;
    @MockBean private CodegenService codegen;

    @Test
    void triggeringImplementTestsReturns202WithRun() throws Exception {
        CodegenRun run = new CodegenRun();
        run.setServiceName("ciam-policies");
        run.setBuildStatus("PASS");
        when(codegen.generate(eq("ciam-policies"), any(), any(), any(), any())).thenReturn(run);

        mvc.perform(post("/api/v1/services/ciam-policies/implement-tests")
                        .contentType("application/json")
                        .content("{\"serviceRepo\":\"/repo\",\"templatePath\":\"/tmpl.md\",\"outputDir\":\"/out\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.serviceName").value("ciam-policies"))
                .andExpect(jsonPath("$.buildStatus").value("PASS"));
    }
}
