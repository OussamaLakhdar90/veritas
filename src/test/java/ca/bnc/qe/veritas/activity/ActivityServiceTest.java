package ca.bnc.qe.veritas.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The feed normalizes every task type into the five plain statuses + attention flags, windows out old
 *  terminal work, keeps live work on top, and persists idempotent acknowledgements. */
@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

    @Mock ScanRepository scans;
    @Mock SnykFixTrainRepository trains;
    @Mock CodegenRunRepository codegenRuns;
    @Mock ActivityAckRepository acks;
    @InjectMocks ActivityService service;

    private static Scan scan(String stage, RunStatus status, Instant started, Instant finished) {
        Scan s = new Scan();
        s.setServiceName("svc");
        s.setStage(stage);
        s.setStatus(status);
        s.setStartedAt(started);
        s.setFinishedAt(finished);
        return s;
    }

    @BeforeEach
    void setUp() {
        // lenient: the acknowledge test never reads the feed repos
        org.mockito.Mockito.lenient().when(scans.findAll()).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(trains.findAll()).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(codegenRuns.findAll()).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(acks.findAll()).thenReturn(List.of());
    }

    @Test
    void mapsEveryTaskTypeToTheFivePlainStatuses() {
        Instant now = Instant.now();
        when(scans.findAll()).thenReturn(List.of(
                scan("QUEUED", RunStatus.RUNNING, now, null),
                scan("RECONCILING", RunStatus.RUNNING, now, null),
                scan("FAILED", RunStatus.FAILED, now, now)));
        SnykFixTrain waiting = new SnykFixTrain();
        waiting.setStatus("AWAITING_CONFIRM");
        waiting.setCoordinate("org.spring:web");
        waiting.setFixedIn("6.1.14");
        waiting.setStartedAt(now);
        when(trains.findAll()).thenReturn(List.of(waiting));
        CodegenRun run = new CodegenRun();
        run.setServiceName("svc");
        run.setBuildStatus("FAIL");
        when(codegenRuns.findAll()).thenReturn(List.of(run));   // createdAt null → windowed out

        List<ActivityItem> feed = service.feed();

        assertThat(feed).extracting(ActivityItem::status).containsExactlyInAnyOrder(
                "QUEUED", "RUNNING", "FAILED", "WAITING_FOR_YOU");
        assertThat(feed).filteredOn(i -> "WAITING_FOR_YOU".equals(i.status()))
                .allSatisfy(i -> assertThat(i.needsAttention()).isTrue());
        assertThat(feed).filteredOn(i -> "FAILED".equals(i.status()))
                .allSatisfy(i -> assertThat(i.needsAttention()).isTrue());
        // Live work sorts before terminal work.
        assertThat(feed.get(feed.size() - 1).status()).isEqualTo("FAILED");
    }

    @Test
    void failedItemsSurfaceTheStoredErrorAsDetail() {
        Instant now = Instant.now();
        Scan failed = scan("FAILED", RunStatus.FAILED, now, now);
        failed.setStageDetail(null);   // a failed scan nulls its live stage sub-line
        failed.setErrorMessage("I/O error on POST api.githubcopilot.com: EOF reached while reading");
        when(scans.findAll()).thenReturn(List.of(failed));

        SnykFixTrain failedTrain = new SnykFixTrain();
        failedTrain.setId("tr-77");
        failedTrain.setStatus("FAILED");
        failedTrain.setCoordinate("com.x:y");
        failedTrain.setFixedIn("2.0");
        failedTrain.setStartedAt(now);
        failedTrain.setFinishedAt(now);
        failedTrain.setErrorMessage("Jira createIssue failed: Field 'summary' cannot be set");
        when(trains.findAll()).thenReturn(List.of(failedTrain));

        List<ActivityItem> feed = service.feed();

        // The row shows WHY it failed instead of a bare "Failed" with no detail.
        assertThat(feed).filteredOn(i -> "SCAN".equals(i.type()))
                .singleElement().satisfies(i -> assertThat(i.detail()).contains("EOF reached while reading"));
        assertThat(feed).filteredOn(i -> "FIX_TRAIN".equals(i.type()))
                .singleElement().satisfies(i -> {
                    assertThat(i.detail()).contains("Field 'summary' cannot be set");
                    // Deep-links to this train's live progress stepper, not the bare /snyk page (the dead click).
                    assertThat(i.link()).isEqualTo("/snyk/fix/tr-77");
                });
    }

    @Test
    void windowsOutOldTerminalWorkButKeepsLiveWorkForever() {
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
        when(scans.findAll()).thenReturn(List.of(
                scan("DONE", RunStatus.COMPLETED, old, old),           // old terminal → hidden
                scan("RECONCILING", RunStatus.RUNNING, old, null)));   // old but LIVE → always shown
        List<ActivityItem> feed = service.feed();
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).status()).isEqualTo("RUNNING");
    }

    @Test
    void openPrTrainsWaitButDoNotDemandAttention() {
        SnykFixTrain prOpen = new SnykFixTrain();
        prOpen.setStatus("PR_OPEN");   // waits on reviewers, not on the user
        prOpen.setCoordinate("c");
        prOpen.setFixedIn("2");
        when(trains.findAll()).thenReturn(List.of(prOpen));
        List<ActivityItem> feed = service.feed();
        assertThat(feed).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo("WAITING_FOR_YOU");
            assertThat(i.needsAttention()).isFalse();
        });
    }

    @Test
    void acknowledgeIsIdempotentAndMarksTheFeed() {
        ActivityAck existing = new ActivityAck();
        existing.setItemId("a1");
        when(acks.findAll()).thenReturn(List.of(existing));

        service.acknowledge(java.util.Arrays.asList("a1", "b2", "", null));   // List.of rejects nulls

        verify(acks, times(1)).save(any(ActivityAck.class));   // only b2 — a1 exists, blanks skipped
    }
}
