package ca.bnc.qe.veritas.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.preflight.ConfigDoctor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The config-readiness endpoint returns the doctor's checks as a JSON array. */
@WebMvcTest(PreflightController.class)
class PreflightControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ConfigDoctor doctor;

    @Test
    void returnsTheReadinessReportAsAnArray() throws Exception {
        when(doctor.report()).thenReturn(List.of());
        mvc.perform(get("/api/v1/preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
