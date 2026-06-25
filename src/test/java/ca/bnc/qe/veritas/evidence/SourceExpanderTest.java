package ca.bnc.qe.veritas.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import ca.bnc.qe.veritas.integration.jira.CreateMeta;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import org.junit.jupiter.api.Test;

/** The convenience-source expander: epic→child-issues JQL (edition-aware) and root page→descendant page ids, fail-soft. */
class SourceExpanderTest {

    private final JiraClient jira = mock(JiraClient.class);
    private final ConfluenceClient confluence = mock(ConfluenceClient.class);

    private SourceExpander expander(String jiraEdition) {
        ConnectionsProperties conn = new ConnectionsProperties();
        conn.getJira().setEdition(jiraEdition);
        return new SourceExpander(jira, confluence, conn);
    }

    @Test
    void serverEpicJqlUsesTheDiscoveredEpicLinkField() {
        when(jira.createMeta("CIAM", "Story")).thenReturn(new CreateMeta(List.of(), "customfield_10001", null));
        assertThat(expander("SERVER_DC").jqlForEpic("CIAM-100")).isEqualTo("cf[10001] = \"CIAM-100\"");
    }

    @Test
    void cloudEpicJqlUsesParentAndSkipsCreateMeta() {
        assertThat(expander("CLOUD").jqlForEpic("CIAM-100")).isEqualTo("parent = \"CIAM-100\"");
        verify(jira, never()).createMeta(any(), any());   // Cloud doesn't need the Epic Link field discovery
    }

    @Test
    void aCreateMetaFailureFallsBackToTheEpicLinkFieldName() {
        when(jira.createMeta(any(), any())).thenThrow(new RuntimeException("createmeta 500"));
        assertThat(expander("SERVER_DC").jqlForEpic("CIAM-100")).isEqualTo("\"Epic Link\" = \"CIAM-100\"");
    }

    @Test
    void rootPageExpandsToDescendantIds() {
        when(confluence.descendants("100", SourceExpander.MAX_TREE_PAGES))
                .thenReturn(List.of(new ConfluencePage("100", "", ""), new ConfluencePage("101", "", "")));
        assertThat(expander("SERVER_DC").pageIdsForRoot("100")).containsExactly("100", "101");
    }

    @Test
    void aConfluenceFailureDegradesToAnEmptyListNotAThrow() {
        when(confluence.descendants(any(), anyInt())).thenThrow(new RuntimeException("confluence down"));
        assertThat(expander("SERVER_DC").pageIdsForRoot("100")).isEmpty();
    }

    @Test
    void aBlankRootIsNoOp() {
        assertThat(expander("SERVER_DC").pageIdsForRoot(" ")).isEmpty();
        verify(confluence, never()).descendants(any(), anyInt());
    }
}
