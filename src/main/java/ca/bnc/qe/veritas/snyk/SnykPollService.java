package ca.bnc.qe.veritas.snyk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final SnykVulnRepository vulns;
    private final SnykAlertRepository alerts;
    private final SnykScanPersistence persistence;
    private final SnykClient client;

    public SnykPollService(SnykWatchRepository watches, SnykSnapshotRepository snapshots, SnykVulnRepository vulns,
                           SnykAlertRepository alerts, SnykScanPersistence persistence, SnykClient client) {
        this.watches = watches;
        this.snapshots = snapshots;
        this.vulns = vulns;
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
        maybeAlert(w, prev, saved, rows);
        return saved;
    }

    /**
     * Raise an alert when the status <b>worsens</b> — not just when the aggregate total or the Critical count grows.
     * Also fires on a severity <b>escalation</b> at an unchanged total (e.g. a medium becomes a high) and on a
     * <b>new</b> Critical/High issue that wasn't present before (even if it replaced a remediated one at the same or
     * a lower count). Otherwise a real regression would be silently swallowed.
     */
    private void maybeAlert(SnykWatch w, SnykSnapshot prev, SnykSnapshot cur, List<SnykVuln> curVulns) {
        int prevTotal = prev == null ? 0 : prev.total();
        boolean firstWithVulns = prev == null && cur.total() > 0;
        boolean newCritical = cur.getCritical() > (prev == null ? 0 : prev.getCritical());
        boolean worsened = cur.total() > prevTotal;
        boolean escalated = prev != null && severityScore(cur) > severityScore(prev);
        String newSevere = prev == null ? null : newSevereSeverity(prev, curVulns);   // "critical" | "high" | null
        if (!firstWithVulns && !newCritical && !worsened && !escalated && newSevere == null) {
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
        a.setMessage(message(w, prevTotal, cur, firstWithVulns, newCritical, newSevere, escalated));
        a.setSeen(false);
        alerts.save(a);
        log.info("Snyk alert [{}] {} ({}) — {}", sev, w.getRepoSlug(), w.getOrgSlug(), a.getMessage());
    }

    /** A severity-weighted score (critical ≫ high ≫ medium ≫ low) so an escalation at an unchanged total is caught. */
    private long severityScore(SnykSnapshot s) {
        return s.getCritical() * 1_000_000_000L + s.getHigh() * 1_000_000L + s.getMedium() * 1_000L + s.getLow();
    }

    /** The most-severe NEW issue (a Critical/High issue id not present in the previous snapshot), or null if none. */
    private String newSevereSeverity(SnykSnapshot prev, List<SnykVuln> curVulns) {
        Set<String> prevIds = vulns.findBySnapshotId(prev.getId()).stream()
                .map(SnykVuln::getIssueId).filter(Objects::nonNull).collect(Collectors.toSet());
        boolean newCrit = curVulns.stream().anyMatch(v -> isSeverity(v, "critical") && isNew(v, prevIds));
        if (newCrit) {
            return "critical";
        }
        return curVulns.stream().anyMatch(v -> isSeverity(v, "high") && isNew(v, prevIds)) ? "high" : null;
    }

    private boolean isSeverity(SnykVuln v, String severity) {
        return v.getSeverity() != null && v.getSeverity().equalsIgnoreCase(severity);
    }

    private boolean isNew(SnykVuln v, Set<String> prevIds) {
        return v.getIssueId() != null && !prevIds.contains(v.getIssueId());
    }

    private String message(SnykWatch w, int prevTotal, SnykSnapshot cur, boolean firstWithVulns,
                           boolean newCritical, String newSevere, boolean escalated) {
        String counts = cur.getCritical() + " critical, " + cur.getHigh() + " high, "
                + cur.getMedium() + " medium, " + cur.getLow() + " low";
        String where = w.getRepoSlug() + " (" + w.getOrgSlug() + ")";
        if (firstWithVulns) {
            return "Now watching " + where + ": " + counts + ".";
        }
        if (newCritical) {
            return "New critical vulnerability in " + where + " — now " + counts + ".";
        }
        if (newSevere != null) {
            return "New " + newSevere + "-severity vulnerability in " + where + " — now " + counts + ".";
        }
        if (cur.total() > prevTotal) {
            return "Vulnerabilities increased in " + where + " (was " + prevTotal + ") — now " + counts + ".";
        }
        if (escalated) {
            return "Vulnerability severity increased in " + where + " — now " + counts + ".";
        }
        return "Vulnerabilities changed in " + where + " — now " + counts + ".";
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
