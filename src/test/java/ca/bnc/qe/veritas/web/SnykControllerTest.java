package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.integration.snyk.SnykOrg;
import ca.bnc.qe.veritas.snyk.SnykIssueView;
import ca.bnc.qe.veritas.snyk.SnykService;
import ca.bnc.qe.veritas.snyk.SnykWatchView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test for the Snyk dashboard API: orgs, watch list, add-watch (201), issues, and manual refresh. */
@WebMvcTest(SnykController.class)
class SnykControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SnykService snyk;

    @Test
    void listsOrgs() throws Exception {
        when(snyk.orgs()).thenReturn(List.of(new SnykOrg("org-1", "app7576", "CIAM Profile")));
        mvc.perform(get("/api/v1/snyk/orgs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("app7576"));
    }

    @Test
    void listsWatchesWithSeverityCounts() throws Exception {
        when(snyk.watchViews()).thenReturn(List.of(new SnykWatchView("w1", "org-1", "app7576", "CIAM Profile",
                "t1", "application-tests", true, 4, 1, 12, 0, 1, 14, null)));
        mvc.perform(get("/api/v1/snyk/watches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].repoSlug").value("application-tests"))
                .andExpect(jsonPath("$[0].critical").value(4));
    }

    @Test
    void addWatchReturns201() throws Exception {
        when(snyk.addWatch(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SnykWatchView("w1", "org-1", "app7576", "CIAM Profile", "t1",
                        "application-tests", true, 0, 0, 0, 0, 0, 0, null));
        mvc.perform(post("/api/v1/snyk/watches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"org-1\",\"orgSlug\":\"app7576\",\"orgName\":\"CIAM Profile\","
                                + "\"targetId\":\"t1\",\"repoSlug\":\"application-tests\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("w1"));
    }

    @Test
    void addWatchByAppReturns201AndTargetsApplicationTests() throws Exception {
        when(snyk.addWatchForApp(anyString(), anyString(), anyString()))
                .thenReturn(new SnykWatchView("w1", "o1", "app7576", "CIAM Profile", "t1",
                        "application-tests", true, 0, 0, 0, 0, 0, 0, null));
        mvc.perform(post("/api/v1/snyk/watches/by-app").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"o1\",\"orgSlug\":\"app7576\",\"orgName\":\"CIAM Profile\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repoSlug").value("application-tests"));
    }

    @Test
    void issuesReportSeverityAndFixInfo() throws Exception {
        when(snyk.latestIssues("w1")).thenReturn(List.of(
                new SnykIssueView("profile-management/pom.xml", "SNYK-1", "critical", "Deserialization",
                        "jackson-databind", "3.1.1", "CVE-1", "CWE-502", 9.2, 298, false, null),
                new SnykIssueView("profile-management/pom.xml", "SNYK-2", "high", "Recursion",
                        "commons-lang3", "3.12.0", "CVE-2", "CWE-674", 7.5, 182, true, "3.18.0")));
        mvc.perform(get("/api/v1/snyk/watches/w1/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].severity").value("critical"))
                .andExpect(jsonPath("$[0].fixable").value(false))
                .andExpect(jsonPath("$[1].fixedIn").value("3.18.0"));
    }

    @Test
    void refreshReturns202AndPollCount() throws Exception {
        when(snyk.refreshAll()).thenReturn(3);
        mvc.perform(post("/api/v1/snyk/refresh"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.polled").value(3));
        verify(snyk).refreshAll();
    }
}
