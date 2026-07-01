package ca.bnc.qe.veritas.snyk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.snyk.SnykClient;
import ca.bnc.qe.veritas.integration.snyk.SnykIssue;
import ca.bnc.qe.veritas.integration.snyk.SnykProjectRef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The 0→N / new-Critical alert logic that drives the dashboard notifications. */
class SnykPollServiceTest {

    private final SnykWatchRepository watches = mock(SnykWatchRepository.class);
    private final SnykSnapshotRepository snapshots = mock(SnykSnapshotRepository.class);
    private final SnykAlertRepository alerts = mock(SnykAlertRepository.class);
    private final SnykScanPersistence persistence = mock(SnykScanPersistence.class);
    private final SnykClient client = mock(SnykClient.class);
    private final SnykPollService service =
            new SnykPollService(watches, snapshots, alerts, persistence, client);

    private SnykWatch watch() {
        SnykWatch w = new SnykWatch();
        w.setOrgId("org-7576");
        w.setOrgSlug("app7576");
        w.setRepoSlug("application-tests");
        w.setTargetId("target-1");
        return w;
    }

    private SnykIssue critical() {
        return new SnykIssue("SNYK-1", "critical", "Deserialization",
                "jackson-databind", "3.1.1", "CVE-1", "CWE-502", 9.2, 298, false, List.of());
    }

    private SnykIssue highFixable() {
        return new SnykIssue("SNYK-2", "high", "Recursion",
                "commons-lang3", "3.12.0", "CVE-2", "CWE-674", 7.5, 182, true, List.of("3.18.0"));
    }

    private void stubClient(SnykIssue... issues) {
        when(client.listProjects(anyString(), anyString()))
                .thenReturn(List.of(new SnykProjectRef("proj-1", "profile-management", "profile-management/pom.xml", "develop")));
        when(client.aggregatedIssues(anyString(), anyString())).thenReturn(List.of(issues));
        when(persistence.save(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firstPollWithVulnsRaisesCriticalAlertAndCountsSeverities() {
        stubClient(critical(), highFixable());
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc(any())).thenReturn(Optional.empty());

        SnykSnapshot snap = service.poll(watch());

        assertThat(snap.getCritical()).isEqualTo(1);
        assertThat(snap.getHigh()).isEqualTo(1);
        assertThat(snap.getFixableCount()).isEqualTo(1);   // only the high is fixable
        ArgumentCaptor<SnykAlert> cap = ArgumentCaptor.forClass(SnykAlert.class);
        verify(alerts).save(cap.capture());
        assertThat(cap.getValue().getSeverity()).isEqualTo("critical");
        assertThat(cap.getValue().getMessage()).contains("Now watching");
    }

    @Test
    void unchangedStatusRaisesNoAlert() {
        stubClient(critical(), highFixable());
        SnykSnapshot prev = new SnykSnapshot();
        prev.setCritical(1);
        prev.setHigh(1);
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc(any())).thenReturn(Optional.of(prev));

        service.poll(watch());

        verify(alerts, never()).save(any());
    }

    @Test
    void aNewCriticalRaisesARedAlert() {
        stubClient(critical(), highFixable());
        SnykSnapshot prev = new SnykSnapshot();
        prev.setHigh(1);   // previously only a high, no critical
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc(any())).thenReturn(Optional.of(prev));

        service.poll(watch());

        ArgumentCaptor<SnykAlert> cap = ArgumentCaptor.forClass(SnykAlert.class);
        verify(alerts).save(cap.capture());
        assertThat(cap.getValue().getSeverity()).isEqualTo("critical");
        assertThat(cap.getValue().getMessage()).contains("New critical");
    }
}
