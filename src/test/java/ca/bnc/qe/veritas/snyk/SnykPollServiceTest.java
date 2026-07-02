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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import ca.bnc.qe.veritas.integration.snyk.SnykClient;
import ca.bnc.qe.veritas.integration.snyk.SnykIssue;
import ca.bnc.qe.veritas.integration.snyk.SnykProjectRef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The 0→N / new-Critical alert logic that drives the dashboard notifications. */
class SnykPollServiceTest {

    private final SnykWatchRepository watches = mock(SnykWatchRepository.class);
    private final SnykSnapshotRepository snapshots = mock(SnykSnapshotRepository.class);
    private final SnykVulnRepository vulns = mock(SnykVulnRepository.class);
    private final SnykAlertRepository alerts = mock(SnykAlertRepository.class);
    private final SnykScanPersistence persistence = mock(SnykScanPersistence.class);
    private final SnykClient client = mock(SnykClient.class);
    private final SnykPollService service =
            new SnykPollService(watches, snapshots, vulns, alerts, persistence, client);

    private SnykVuln vuln(String issueId, String severity) {
        SnykVuln v = new SnykVuln();
        v.setIssueId(issueId);
        v.setSeverity(severity);
        return v;
    }

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
        // Same issue ids as the current poll → nothing new/escalated.
        when(vulns.findBySnapshotId(any())).thenReturn(List.of(vuln("SNYK-1", "critical"), vuln("SNYK-2", "high")));

        service.poll(watch());

        verify(alerts, never()).save(any());
    }

    @Test
    void severityEscalationAtAnUnchangedTotalRaisesAnAlert() {
        // The same issue (SNYK-2) escalates medium → high: total is still 1, so a naive total-diff would miss it.
        stubClient(highFixable());   // SNYK-2, now "high"
        SnykSnapshot prev = new SnykSnapshot();
        prev.setMedium(1);
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc(any())).thenReturn(Optional.of(prev));
        when(vulns.findBySnapshotId(any())).thenReturn(List.of(vuln("SNYK-2", "medium")));

        service.poll(watch());

        ArgumentCaptor<SnykAlert> cap = ArgumentCaptor.forClass(SnykAlert.class);
        verify(alerts).save(cap.capture());
        assertThat(cap.getValue().getMessage()).contains("severity increased");
    }

    @Test
    void aNewSevereIssueReplacingAFixedOneAtTheSameCountRaisesAnAlert() {
        // A high (SNYK-3) replaces a remediated high (SNYK-OLD): count 2 == 2, but a NEW severe issue appeared.
        SnykIssue high3 = new SnykIssue("SNYK-3", "high", "XXE", "rhino", "1.7.14", "CVE-3", "CWE-611",
                7.1, 150, true, List.of("1.7.15"));
        stubClient(highFixable(), high3);   // SNYK-2 + SNYK-3
        SnykSnapshot prev = new SnykSnapshot();
        prev.setHigh(2);
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc(any())).thenReturn(Optional.of(prev));
        when(vulns.findBySnapshotId(any())).thenReturn(List.of(vuln("SNYK-2", "high"), vuln("SNYK-OLD", "high")));

        service.poll(watch());

        ArgumentCaptor<SnykAlert> cap = ArgumentCaptor.forClass(SnykAlert.class);
        verify(alerts).save(cap.capture());
        assertThat(cap.getValue().getMessage()).contains("New high-severity");
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

    @Test
    void aConcurrentPollOfTheSameWatchIsSkippedNotDuplicated() throws InterruptedException {
        SnykWatch w = watch();
        w.setId("w-lock-1");
        SnykSnapshot latest = new SnykSnapshot();
        when(snapshots.findFirstByWatchIdOrderByTakenAtDesc("w-lock-1")).thenReturn(Optional.of(latest));

        // Hold this watch's lock on ANOTHER thread (a ReentrantLock is reentrant, so the test thread can't stand in).
        ReentrantLock held = new ReentrantLock();
        service.pollLocks.put("w-lock-1", held);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            held.lock();
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                held.unlock();
            }
        });
        other.start();
        acquired.await();   // the other thread now holds the lock

        SnykSnapshot result = service.poll(w);

        release.countDown();
        other.join();
        // It bailed out with the existing latest snapshot instead of racing in a duplicate snapshot + alert.
        assertThat(result).isSameAs(latest);
        verify(persistence, never()).save(any(), any());
        verify(alerts, never()).save(any());
    }
}
