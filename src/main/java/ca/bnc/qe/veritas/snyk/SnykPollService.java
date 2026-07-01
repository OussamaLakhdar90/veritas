package ca.bnc.qe.veritas.snyk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.integration.snyk.SnykClient;
import ca.bnc.qe.veritas.integration.snyk.SnykIssue;
import ca.bnc.qe.veritas.integration.snyk.SnykProjectRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Reads every watched repo's current Snyk vulnerabilities, stores a snapshot, and raises a {@link SnykAlert} when
 * the status worsens versus the previous snapshot — the "0 → N" (or new-Critical) trigger the user asked for.
 * Deterministic, no LLM. Polling is driven by {@link SnykPoller} (scheduled) or a manual refresh.
 */
@Service
@Slf4j
public class SnykPollService {

    private final SnykWatchRepository watches;
    private final SnykSnapshotRepository snapshots;
    private final SnykAlertRepository alerts;
    private final SnykScanPersistence persistence;
    private final SnykClient client;

    public SnykPollService(SnykWatchRepository watches, SnykSnapshotRepository snapshots, SnykAlertRepository alerts,
                           SnykScanPersistence persistence, SnykClient client) {
        this.watches = watches;
        this.snapshots = snapshots;
        this.alerts = alerts;
        this.persistence = persistence;
        this.client = client;
    }

    /** Poll every enabled watch; one repo failing never aborts the others. */
    public void pollAll() {
        for (SnykWatch w : watches.findByEnabledTrue()) {
            try {
                poll(w);
            } catch (RuntimeException e) {
                log.warn("Snyk poll failed for {} ({}): {}", w.getRepoSlug(), w.getOrgSlug(), e.getMessage());
            }
        }
    }

    /** Poll one watch: fetch all its projects' issues, store a snapshot, and alert if the status worsened. */
    public SnykSnapshot poll(SnykWatch w) {
        SnykSnapshot prev = snapshots.findFirstByWatchIdOrderByTakenAtDesc(w.getId()).orElse(null);
        SnykSnapshot snap = new SnykSnapshot();
        snap.setWatchId(w.getId());
        snap.setTakenAt(Instant.now());

        List<SnykVuln> rows = new ArrayList<>();
        int projectCount = 0;
        int fixable = 0;
        for (SnykProjectRef p : client.listProjects(w.getOrgId(), w.getTargetId())) {
            projectCount++;
            for (SnykIssue issue : client.aggregatedIssues(w.getOrgId(), p.id())) {
                rows.add(toVuln(issue, p, snap));
                bumpSeverity(snap, issue.severity());
                if (issue.fixable()) {
                    fixable++;
                }
            }
        }
        snap.setProjectCount(projectCount);
        snap.setFixableCount(fixable);

        SnykSnapshot saved = persistence.save(snap, rows);
        maybeAlert(w, prev, saved);
        return saved;
    }

    private void maybeAlert(SnykWatch w, SnykSnapshot prev, SnykSnapshot cur) {
        int prevTotal = prev == null ? 0 : prev.total();
        int prevCritical = prev == null ? 0 : prev.getCritical();
        boolean firstWithVulns = prev == null && cur.total() > 0;
        boolean newCritical = cur.getCritical() > prevCritical;
        boolean worsened = cur.total() > prevTotal;
        if (!firstWithVulns && !newCritical && !worsened) {
            return;
        }
        String sev = cur.getCritical() > 0 ? "critical"
                : cur.getHigh() > 0 ? "high"
                : cur.getMedium() > 0 ? "medium" : "low";
        SnykAlert a = new SnykAlert();
        a.setWatchId(w.getId());
        a.setOrgSlug(w.getOrgSlug());
        a.setRepoSlug(w.getRepoSlug());
        a.setSeverity(sev);
        a.setMessage(message(w, prevTotal, cur, newCritical, firstWithVulns));
        a.setSeen(false);
        alerts.save(a);
        log.info("Snyk alert [{}] {} ({}) — {}", sev, w.getRepoSlug(), w.getOrgSlug(), a.getMessage());
    }

    private String message(SnykWatch w, int prevTotal, SnykSnapshot cur, boolean newCritical, boolean firstWithVulns) {
        String counts = cur.getCritical() + " critical, " + cur.getHigh() + " high, "
                + cur.getMedium() + " medium, " + cur.getLow() + " low";
        String where = w.getRepoSlug() + " (" + w.getOrgSlug() + ")";
        if (firstWithVulns) {
            return "Now watching " + where + ": " + counts + ".";
        }
        if (newCritical) {
            return "New critical vulnerability in " + where + " — now " + counts + ".";
        }
        return "Vulnerabilities increased in " + where + " (was " + prevTotal + ") — now " + counts + ".";
    }

    private void bumpSeverity(SnykSnapshot snap, String severity) {
        switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> snap.setCritical(snap.getCritical() + 1);
            case "high" -> snap.setHigh(snap.getHigh() + 1);
            case "medium" -> snap.setMedium(snap.getMedium() + 1);
            case "low" -> snap.setLow(snap.getLow() + 1);
            default -> {
                // unknown severity — counted only in the raw vuln rows, not the severity KPIs
            }
        }
    }

    private SnykVuln toVuln(SnykIssue issue, SnykProjectRef project, SnykSnapshot snap) {
        SnykVuln v = new SnykVuln();
        v.setSnapshotId(snap.getId());
        v.setProjectId(project.id());
        v.setProjectName(project.targetFile() == null || project.targetFile().isBlank()
                ? project.name() : project.targetFile());
        v.setIssueId(issue.issueId());
        v.setSeverity(issue.severity());
        v.setTitle(issue.title());
        v.setPkgName(issue.pkgName());
        v.setPkgVersion(issue.pkgVersion());
        v.setCve(issue.cve());
        v.setCwe(issue.cwe());
        v.setCvss(issue.cvss());
        v.setRiskScore(issue.riskScore());
        v.setFixable(issue.fixable());
        v.setFixedIn(issue.safeVersion());
        return v;
    }
}
