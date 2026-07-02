package ca.bnc.qe.veritas.snyk;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.snyk.SnykClient;
import ca.bnc.qe.veritas.integration.snyk.SnykOrg;
import ca.bnc.qe.veritas.integration.snyk.SnykTarget;
import ca.bnc.qe.veritas.skill.NotFoundException;
import ca.bnc.qe.veritas.snyk.fix.FrameworkProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service behind the Snyk dashboard: browse orgs/repos, manage watches, read the latest snapshot's
 * issues and the alert feed, and trigger a manual refresh. Deterministic — no LLM.
 */
@Service
public class SnykService {

    private static final Map<String, Integer> SEVERITY_RANK =
            Map.of("critical", 0, "high", 1, "medium", 2, "low", 3);

    private final SnykClient client;
    private final SnykWatchRepository watches;
    private final SnykSnapshotRepository snapshots;
    private final SnykVulnRepository vulns;
    private final SnykAlertRepository alerts;
    private final SnykPollService pollService;
    private final FrameworkProperties framework;

    public SnykService(SnykClient client, SnykWatchRepository watches, SnykSnapshotRepository snapshots,
                       SnykVulnRepository vulns, SnykAlertRepository alerts, SnykPollService pollService,
                       FrameworkProperties framework) {
        this.client = client;
        this.watches = watches;
        this.snapshots = snapshots;
        this.vulns = vulns;
        this.alerts = alerts;
        this.pollService = pollService;
        this.framework = framework;
    }

    public List<SnykOrg> orgs() {
        return client.listOrgs();
    }

    public List<SnykTarget> repos(String orgId) {
        return client.listTargets(orgId);
    }

    /** The canonical consumer-tests target for an org (the repo holding the consumer poms, default application-tests). */
    public Optional<SnykTarget> resolveApplicationTestsTarget(String orgId) {
        String slug = framework.getConsumerRepo().toLowerCase(Locale.ROOT);
        List<SnykTarget> targets = client.listTargets(orgId);
        return targets.stream().filter(t -> framework.getConsumerRepo().equalsIgnoreCase(t.displayName())).findFirst()
                .or(() -> targets.stream()
                        .filter(t -> t.displayName() != null
                                && t.displayName().toLowerCase(Locale.ROOT).contains(slug))
                        .findFirst());
    }

    /** Watch an app-id by auto-targeting its consumer-tests repo (default {@code application-tests}). */
    public SnykWatchView addWatchForApp(String orgId, String orgSlug, String orgName) {
        SnykTarget target = resolveApplicationTestsTarget(orgId).orElseThrow(() -> new IllegalStateException(
                "No '" + framework.getConsumerRepo() + "' repository found in "
                        + (orgSlug == null ? orgId : orgSlug) + "."));
        return addWatch(orgId, orgSlug, orgName, target.id(), target.displayName());
    }

    /** Add a watch (idempotent on org+target); returns its view. */
    public SnykWatchView addWatch(String orgId, String orgSlug, String orgName, String targetId, String repoSlug) {
        SnykWatch w = watches.findByOrgIdAndTargetId(orgId, targetId).orElseGet(SnykWatch::new);
        w.setOrgId(orgId);
        w.setOrgSlug(orgSlug);
        w.setOrgName(orgName);
        w.setTargetId(targetId);
        w.setRepoSlug(repoSlug);
        w.setEnabled(true);
        return view(watches.save(w));
    }

    /** Remove a watch and its owned snapshots/vulns/alerts (so a deleted watch never orphans rows). 404 if unknown. */
    @Transactional
    public void removeWatch(String id) {
        if (!watches.existsById(id)) {
            throw new NotFoundException("Watch not found: " + id);
        }
        for (SnykSnapshot s : snapshots.findByWatchId(id)) {
            vulns.deleteBySnapshotId(s.getId());
        }
        snapshots.deleteByWatchId(id);
        alerts.deleteByWatchId(id);
        watches.deleteById(id);
    }

    public List<SnykWatchView> watchViews() {
        List<SnykWatch> all = watches.findAll();
        if (all.isEmpty()) {
            return List.of();
        }
        // Latest snapshot per watch in ONE query (newest-first → the first seen per watch is its latest) — no N+1.
        Map<String, SnykSnapshot> latest = new HashMap<>();
        for (SnykSnapshot s : snapshots.findByWatchIdInOrderByTakenAtDesc(all.stream().map(SnykWatch::getId).toList())) {
            latest.putIfAbsent(s.getWatchId(), s);
        }
        return all.stream().map(w -> view(w, latest.get(w.getId()))).toList();
    }

    /** The latest snapshot's vulnerabilities for a watch, most-severe first. 404 if the watch is unknown. */
    public List<SnykIssueView> latestIssues(String watchId) {
        if (!watches.existsById(watchId)) {
            throw new NotFoundException("Watch not found: " + watchId);
        }
        return snapshots.findFirstByWatchIdOrderByTakenAtDesc(watchId)
                .map(snap -> vulns.findBySnapshotId(snap.getId()).stream()
                        .sorted(Comparator.comparingInt((SnykVuln v) -> severityRank(v.getSeverity()))
                                .thenComparing(Comparator.comparingInt(SnykVuln::getRiskScore).reversed()))
                        .map(this::issueView)
                        .toList())
                .orElseGet(List::of);
    }

    /** Poll every enabled watch now; returns how many were polled. */
    public int refreshAll() {
        List<SnykWatch> enabled = watches.findByEnabledTrue();
        pollService.pollAll();
        return enabled.size();
    }

    public void refresh(String watchId) {
        SnykWatch w = watches.findById(watchId)
                .orElseThrow(() -> new NotFoundException("Watch not found: " + watchId));
        pollService.poll(w);
    }

    public List<SnykAlertView> alerts(boolean unseenOnly) {
        List<SnykAlert> raw = unseenOnly
                ? alerts.findBySeenFalseOrderByCreatedAtDesc() : alerts.findAllByOrderByCreatedAtDesc();
        return raw.stream().map(SnykAlertView::of).toList();
    }

    public void markSeen(String id) {
        SnykAlert a = alerts.findById(id).orElseThrow(() -> new NotFoundException("Alert not found: " + id));
        a.setSeen(true);
        alerts.save(a);
    }

    private SnykWatchView view(SnykWatch w) {
        return view(w, snapshots.findFirstByWatchIdOrderByTakenAtDesc(w.getId()).orElse(null));
    }

    private SnykWatchView view(SnykWatch w, SnykSnapshot snap) {
        return new SnykWatchView(w.getId(), w.getOrgId(), w.getOrgSlug(), w.getOrgName(), w.getTargetId(),
                w.getRepoSlug(), w.isEnabled(),
                snap == null ? 0 : snap.getCritical(), snap == null ? 0 : snap.getHigh(),
                snap == null ? 0 : snap.getMedium(), snap == null ? 0 : snap.getLow(),
                snap == null ? 0 : snap.getFixableCount(), snap == null ? 0 : snap.getProjectCount(),
                snap == null ? null : snap.getTakenAt());
    }

    private SnykIssueView issueView(SnykVuln v) {
        return new SnykIssueView(v.getProjectName(), v.getIssueId(), v.getSeverity(), v.getTitle(),
                v.getPkgName(), v.getPkgVersion(), v.getCve(), v.getCwe(), v.getCvss(), v.getRiskScore(),
                v.isFixable(), v.getFixedIn());
    }

    private int severityRank(String severity) {
        return SEVERITY_RANK.getOrDefault(severity == null ? "" : severity.toLowerCase(Locale.ROOT), 4);
    }
}
