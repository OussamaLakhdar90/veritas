package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.bnc.qe.veritas.contract.AsyncScanRunner;
import ca.bnc.qe.veritas.contract.Thoroughness;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** POST /scans returns 202 and threads the per-scan thoroughness (default STANDARD) to the async runner. */
@WebMvcTest(ScanController.class)
class ScanControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private AsyncScanRunner runner;

    @Test
    void postScanPassesThoroughnessAndReturns202() throws Exception {
        when(runner.submit(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn("scan-1");

        mvc.perform(post("/api/v1/scans").contentType("application/json").content(
                        "{\"serviceName\":\"svc\",\"appId\":\"APP\",\"repoSlug\":\"r\",\"specPaths\":[\"openapi.yaml\"],\"thoroughness\":\"DEEP\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scanId").value("scan-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(runner).submit(eq("svc"), eq("APP"), eq("r"), any(), any(), any(), anyBoolean(), eq("api"),
                eq(Thoroughness.DEEP));
    }

    @Test
    void thoroughnessDefaultsToStandardWhenOmitted() throws Exception {
        when(runner.submit(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn("scan-2");

        mvc.perform(post("/api/v1/scans").contentType("application/json")
                        .content("{\"serviceName\":\"svc\",\"specPaths\":[\"openapi.yaml\"]}"))
                .andExpect(status().isAccepted());

        verify(runner).submit(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), eq(Thoroughness.STANDARD));
    }
}
