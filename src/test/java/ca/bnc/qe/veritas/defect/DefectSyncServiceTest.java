package ca.bnc.qe.veritas.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraStatus;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.DefectLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class DefectSyncServiceTest {

    @Autowired private DefectSyncService syncService;
    @Autowired private DefectLinkRepository defects;
    @MockBean private JiraClient jira;

    @Test
    void cachesLatestJiraStatusOnEachLink() {
        DefectLink link = new DefectLink();
        link.setFindingId("f-" + java.util.UUID.randomUUID());
        link.setJiraKey("CIAM-501");
        link.setCreatedInJira(true);
        defects.save(link);

        when(jira.getStatus(eq("CIAM-501"))).thenReturn(new JiraStatus("In Progress", "indeterminate"));

        int updated = syncService.syncAll();
        assertThat(updated).isGreaterThanOrEqualTo(1);

        DefectLink refreshed = defects.findByFindingId(link.getFindingId()).orElseThrow();
        assertThat(refreshed.getJiraStatus()).isEqualTo("In Progress");
        assertThat(refreshed.getJiraStatusCategory()).isEqualTo("indeterminate");
        assertThat(refreshed.getLastSyncedAt()).isNotNull();

        // second sync with the same status reports no change
        assertThat(syncService.sync(refreshed)).isFalse();
    }
}
