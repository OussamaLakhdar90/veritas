package ca.bnc.qe.veritas.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.persistence.DefectLink;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.secret.SecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class DefectServiceTest {

    @Autowired private DefectService defectService;
    @Autowired private ScanRepository scanRepository;
    @Autowired private FindingRecordRepository findingRepository;

    @MockBean private JiraClient jiraClient;
    @MockBean private SecretProvider secrets;

    @BeforeEach
    void seedToken() {
        when(secrets.get("JIRA_API_TOKEN")).thenReturn(Optional.of("pat-123"));   // satisfies the write-scope preflight
    }

    @Test
    void createsDefectAndIsIdempotent() {
        when(jiraClient.createIssue(any())).thenReturn("CIAM-777");

        Scan scan = new Scan();
        scan.setServiceName("ciam-policies");
        scan.setStatus(RunStatus.COMPLETED);
        scan = scanRepository.save(scan);

        FindingRecord finding = new FindingRecord();
        finding.setScanId(scan.getId());
        finding.setType("MISSING_ENDPOINT");
        finding.setSeverity("CRITICAL");
        finding.setLayer("L2");
        finding.setEndpoint("POST /v1/transfers");
        finding.setStatus("OPEN");
        finding = findingRepository.save(finding);

        DefectLink link = defectService.createDefect(finding.getId(), "CIAM", "Bug", "tester");
        assertThat(link.getJiraKey()).isEqualTo("CIAM-777");
        assertThat(link.isCreatedInJira()).isTrue();
        assertThat(findingRepository.findById(finding.getId()).orElseThrow().getStatus()).isEqualTo("JIRA_CREATED");

        DefectLink again = defectService.createDefect(finding.getId(), "CIAM", "Bug", "tester");
        assertThat(again.getId()).isEqualTo(link.getId());
        verify(jiraClient, times(1)).createIssue(any());
    }
}
