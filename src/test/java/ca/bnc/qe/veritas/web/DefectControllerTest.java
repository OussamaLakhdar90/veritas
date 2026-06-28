package ca.bnc.qe.veritas.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.defect.DefectService;
import ca.bnc.qe.veritas.defect.DefectSyncService;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test: the Defects dashboard reads GET /api/v1/defects (newest first, with Jira status). */
@WebMvcTest(DefectController.class)
class DefectControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DefectService defectService;

    @MockBean
    private DefectSyncService syncService;

    @MockBean
    private DefectLinkRepository defectLinks;

    @Test
    void listsLinkedDefectsNewestFirst() throws Exception {
        DefectLink d = new DefectLink();
        d.setFindingId("f1");
        d.setJiraKey("CIAM-42");
        d.setJiraUrl("https://jira/CIAM-42");
        d.setJiraStatus("In Progress");
        d.setJiraStatusCategory("indeterminate");
        d.setCreatedInJira(true);
        when(defectLinks.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(d));

        mvc.perform(get("/api/v1/defects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jiraKey").value("CIAM-42"))
                .andExpect(jsonPath("$[0].jiraStatusCategory").value("indeterminate"))
                .andExpect(jsonPath("$[0].createdInJira").value(true));
    }

    @Test
    void exposesAggregateDefectMetrics() throws Exception {
        DefectLink open = new DefectLink();
        open.setSeverity("HIGH");
        open.setServiceName("ciam-policies");
        open.setJiraStatusCategory("in progress");
        DefectLink closed = new DefectLink();
        closed.setSeverity("HIGH");
        closed.setServiceName("ciam-policies");
        closed.setJiraStatusCategory("done");
        when(defectLinks.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(open, closed));

        mvc.perform(get("/api/v1/defects/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.closed").value(1))
                .andExpect(jsonPath("$.bySeverity.HIGH").value(2))
                .andExpect(jsonPath("$.byService['ciam-policies']").value(2));
    }
}
