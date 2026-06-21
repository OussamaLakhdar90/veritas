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
}
