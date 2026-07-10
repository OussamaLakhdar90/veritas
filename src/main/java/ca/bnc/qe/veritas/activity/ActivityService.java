package ca.bnc.qe.veritas.activity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import ca.bnc.qe.veritas.persistence.CodegenRunRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.snyk.fix.SnykFixStatus;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrain;
import ca.bnc.qe.veritas.snyk.fix.SnykFixTrainRepository;
import org.springframework.stereotype.Service;

/**
 * The unified activity feed: every long-running task the platform knows about (contract scans, Snyk fix
 * trains, test-code generations) normalized into FIVE plain statuses a non-technical user can read —
 * QUEUED · RUNNING · WAITING_FOR_YOU · COMPLETED · FAILED — plus a needsAttention flag for anything a
 * human must act on. The SERVER is the source of truth (no more localStorage dock that forgets scans on
 * reload); acknowledgements persist per item so a dismissed card stays dismissed across browsers.
 *
 * Query-time fan-in over the existing tables — no new task infrastructure, one cheap poll.
 */
@Service
public class ActivityService {

    /** Terminal items older than this stop appearing (non-terminal ones always show). */
    private static final int WINDOW_DAYS = 7;
    private static final int MAX_ITEMS = 50;
    private static final Set<String> TRAIN_WAITING = Set.of(
            SnykFixStatus.AWAITING_CONFIRM, SnykFixStatus.AWAITING_MANUAL_FIX, SnykFixStatus.PR_OPEN);

    private final ScanRepository scans;
    private final SnykFixTrainRepository trains;
    private final CodegenRunRepository codegenRuns;
    private final ActivityAckRepository acks;

    public ActivityService(ScanRepository scans, SnykFixTrainRepository trains,
                           CodegenRunRepository codegenRuns, ActivityAckRepository acks) {
        this.scans = scans;
        this.trains = trains;
        this.codegenRuns = codegenRuns;
        this.acks = acks;
    }

    public List<ActivityItem> feed() {
        Instant cutoff = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
        Set<String> acked = acks.findAll().stream().map(ActivityAck::getItemId).collect(Collectors.toSet());
        List<ActivityItem> out = new ArrayList<>();
        for (Scan s : scans.findAll()) {
            addScan(out, s, cutoff, acked);
        }
        for (SnykFixTrain t : trains.findAll()) {
            addTrain(out, t, cutoff, acked);
        }
        for (CodegenRun r : codegenRuns.findAll()) {
            addCodegen(out, r, cutoff, acked);
        }
        // Live work first, then newest — the dock reads top-down.
        out.sort(Comparator
                .comparing((ActivityItem i) -> i.terminal() ? 1 : 0)
                .thenComparing(ActivityItem::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > MAX_ITEMS ? out.subList(0, MAX_ITEMS) : out;
    }

    /** Persist acknowledgements (idempotent) — a dismissed card stays dismissed on every browser. */
    public void acknowledge(List<String> itemIds) {
        Set<String> existing = acks.findAll().stream().map(ActivityAck::getItemId).collect(Collectors.toSet());
        for (String id : itemIds) {
            if (id != null && !id.isBlank() && !existing.contains(id)) {
                ActivityAck ack = new ActivityAck();
                ack.setItemId(id);
                acks.save(ack);
            }
        }
    }

    private void addScan(List<ActivityItem> out, Scan s, Instant cutoff, Set<String> acked) {
        String status;
        boolean attention = false;
        if (s.getStatus() == RunStatus.RUNNING) {
            status = "QUEUED".equals(s.getStage()) ? ActivityItem.QUEUED : ActivityItem.RUNNING;
        } else if (s.getStatus() == RunStatus.FAILED) {
            status = ActivityItem.FAILED;
            attention = true;
        } else {
            status = ActivityItem.COMPLETED;
        }
        if (skip(status, s.getFinishedAt(), cutoff)) {
            return;
        }
        out.add(new ActivityItem(s.getId(), "SCAN", s.getServiceName(), status, s.getStage(),
                failDetail(status, s.getErrorMessage(), s.getStageDetail()), attention,
                s.getStartedAt(), s.getFinishedAt(), "/findings/" + s.getId(), acked.contains(s.getId())));
    }

    private void addTrain(List<ActivityItem> out, SnykFixTrain t, Instant cutoff, Set<String> acked) {
        String status;
        boolean attention = false;
        if (SnykFixStatus.DONE.equals(t.getStatus())) {
            status = ActivityItem.COMPLETED;
        } else if (SnykFixStatus.FAILED.equals(t.getStatus())) {
            status = ActivityItem.FAILED;
            attention = true;
        } else if (TRAIN_WAITING.contains(t.getStatus())) {
            status = ActivityItem.WAITING_FOR_YOU;
            attention = !SnykFixStatus.PR_OPEN.equals(t.getStatus());   // open PRs wait on reviewers, not on you
        } else {
            status = ActivityItem.RUNNING;
        }
        if (skip(status, t.getFinishedAt(), cutoff)) {
            return;
        }
        out.add(new ActivityItem(t.getId(), "FIX_TRAIN", t.getCoordinate() + " → " + t.getFixedIn(), status,
                t.getStatus(), failDetail(status, t.getErrorMessage(), t.getStageDetail()), attention,
                // Deep-link straight to this train's live progress stepper, not the bare Snyk page (which showed
                // nothing and read as a dead click). The dashboard route is /snyk/fix/{trainId}.
                t.getStartedAt(), t.getFinishedAt(), "/snyk/fix/" + t.getId(), acked.contains(t.getId())));
    }

    private void addCodegen(List<ActivityItem> out, CodegenRun r, Instant cutoff, Set<String> acked) {
        // Codegen has no live RUNNING state yet (it completes within one request) — it appears when finished.
        boolean failed = "FAIL".equals(r.getBuildStatus());
        String status = failed ? ActivityItem.FAILED : ActivityItem.COMPLETED;
        if (skip(status, r.getCreatedAt(), cutoff)) {
            return;
        }
        out.add(new ActivityItem(r.getId(), "CODEGEN", r.getServiceName(), status, r.getBuildStatus(),
                null, failed, r.getCreatedAt(), r.getCreatedAt(), "/codegen", acked.contains(r.getId())));
    }

    /**
     * On a FAILED item, show WHY (the stored error) as the feed detail so the user isn't left with a bare "Failed"
     * that only bounces them back to the screen; otherwise the live stage sub-line. Failed items null their stageDetail,
     * so without this the row has no detail at all.
     */
    private static String failDetail(String status, String errorMessage, String stageDetail) {
        if (ActivityItem.FAILED.equals(status) && errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        return stageDetail;
    }

    /** Terminal + old (or timestamp-less) → out of the window. Non-terminal always shows. */
    private static boolean skip(String status, Instant finishedAt, Instant cutoff) {
        boolean terminal = ActivityItem.COMPLETED.equals(status) || ActivityItem.FAILED.equals(status);
        return terminal && (finishedAt == null || finishedAt.isBefore(cutoff));
    }
}
