package ca.bnc.qe.veritas.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The Jira picker endpoint: text queries become a summary search; a pasted key/URL becomes an exact key lookup. */
@WebMvcTest(JiraController.class)
class JiraControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private JiraClient jira;

    private static JiraIssue issue(String key, String summary) {
        return new JiraIssue(key, summary, null, null, null, List.of(), List.of(), List.of());
    }

    @Test
    void textQueryBecomesASummarySearch() throws Exception {
        when(jira.search(ArgumentMatchers.contains("summary ~ \"policy regression\""), eq(List.of("summary")), eq(10)))
                .thenReturn(List.of(issue("CIAM-1842", "Automate policy API regression tests")));

        mvc.perform(get("/api/v1/jira/search").param("q", "policy regression"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("CIAM-1842"))
                .andExpect(jsonPath("$[0].summary").value("Automate policy API regression tests"));
    }

    @Test
    void aPastedBrowseUrlIsResolvedByKey() throws Exception {
        when(jira.search(eq("key = \"CIAM-1842\""), eq(List.of("summary")), eq(10)))
                .thenReturn(List.of(issue("CIAM-1842", "Automate policy API regression tests")));

        mvc.perform(get("/api/v1/jira/search").param("q", "https://jira.example/browse/CIAM-1842"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("CIAM-1842"));

        verify(jira).search(eq("key = \"CIAM-1842\""), eq(List.of("summary")), eq(10));
    }

    @Test
    void blankQueryReturnsAnEmptyListWithoutHittingJira() throws Exception {
        mvc.perform(get("/api/v1/jira/search").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
