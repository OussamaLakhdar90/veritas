package ca.bnc.qe.veritas.snyk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.snyk.SnykClient;
import ca.bnc.qe.veritas.integration.snyk.SnykOrg;
import ca.bnc.qe.veritas.integration.snyk.SnykTarget;
import org.springframework.stereotype.Service;

/**
 * Application service behind the Snyk dashboard: browse orgs/repos, manage watches, read the latest snapshot's
 * issues and the alert feed, and trigger a manual refresh. Deterministic — no LLM.
 */
@Service
public class SnykService {

    private static final Map<String, Integer> SEVERITY_RANK =
            Map.of("critical", 0, "high", 1, "medium", 2, "low", 3);
    private static final String APPLICATION_TESTS = "application-tests";

    private final SnykClient client;
    private final SnykWatchRepository watches;
    private final SnykSnapshotRepository snapshots;
    private final SnykVulnRepository vulns;
    private final SnykAlertRepository alerts;
    private final SnykPollService pollService;

    public SnykService(SnykClient client, SnykWatchRepository watches, SnykSnapshotRepository snapshots,
                       SnykVulnRepository vulns, SnykAlertRepository alerts, SnykPollService pollService) {
        this.client = client;
        this.watches = watches;
        this.snapshots = snapshots;
        this.vulns = vulns;
        this.alerts = alerts;
        this.pollService = pollService;
    }

    public List<SnykOrg> orgs() {
        return client.listOrgs();
    }

    public List<SnykTarget> repos(String orgId) {
        return client.listTargets(orgId);
    }

    /** The canonical {@code application-tests} target for an org (the repo holding the consumer poms). */
    public Optional<SnykTarget> resolveApplicationTestsTarget(String orgId) {
        List<SnykTarget> targets = client.listTargets(orgId);
        return targets.stream().filter(t -> APPLICATION_TESTS.equalsIgnoreCase(t.displayName())).findFirst()
                .or(() -> targets.stream()
                        .filter(t -> t.displayName() != null
                                && t.displayName().toLowerCase(Locale.ROOT).contains(APPLICATION_TESTS))
                        .findFirst());
    }

    /** Watch an app-id by auto-targeting its {@code application-tests} repo. */
    public SnykWatchView addWatchForApp(String orgId, String orgSlug, String orgName) {
        SnykTarget target = resolveApplicationTestsTarget(orgId).orElseThrow(() -> new IllegalStateException(
                "No 'application-tests' repository found in " + (orgSlug == null ? orgId : orgSlug) + "."));
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

    public void removeWatch(String id) {
        watches.deleteById(id);
    }

    public List<SnykWatchView> watchViews() {
        List<SnykWatchView> out = new ArrayList<>();
        for (SnykWatch w : watches.findAll()) {
            out.add(view(w));
        }
        return out;
    }

    /** The latest snapshot's vulnerabilities for a watch, most-severe first. */
    public List<SnykIssueView> latestIssues(String watchId) {
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
        watches.findById(watchId).ifPresent(pollService::poll);
    }

    public List<SnykAlert> alerts(boolean unseenOnly) {
        return unseenOnly ? alerts.findBySeenFalseOrderByCreatedAtDesc() : alerts.findAllByOrderByCreatedAtDesc();
    }

    public void markSeen(String id) {
        alerts.findById(id).ifPresent(a -> {
            a.setSeen(true);
            alerts.save(a);
        });
    }

    private SnykWatchView view(SnykWatch w) {
        SnykSnapshot snap = snapshots.findFirstByWatchIdOrderByTakenAtDesc(w.getId()).orElse(null);
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
