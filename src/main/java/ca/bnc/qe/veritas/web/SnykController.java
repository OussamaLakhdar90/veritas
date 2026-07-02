package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.integration.snyk.SnykOrg;
import ca.bnc.qe.veritas.integration.snyk.SnykTarget;
import ca.bnc.qe.veritas.snyk.SnykAlertView;
import ca.bnc.qe.veritas.snyk.SnykIssueView;
import ca.bnc.qe.veritas.snyk.SnykService;
import ca.bnc.qe.veritas.snyk.SnykSummaryService;
import ca.bnc.qe.veritas.snyk.SnykSummaryView;
import ca.bnc.qe.veritas.snyk.SnykWatchView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Snyk dependency-security dashboard API: browse orgs/repos, manage watches, read the latest issues + alert feed,
 * and trigger a manual refresh. All read-only against Snyk; the watch/alert state is Veritas's own.
 */
@RestController
@RequestMapping("/api/v1")
public class SnykController {

    private final SnykService snyk;
    private final SnykSummaryService summary;

    public SnykController(SnykService snyk, SnykSummaryService summary) {
        this.snyk = snyk;
        this.summary = summary;
    }

    /** Managerial roll-up (found vs fixed, PRs opened, LLM spend) for the executive dashboard + the Snyk page. */
    @GetMapping("/snyk/summary")
    public SnykSummaryView summary() {
        return summary.summary();
    }

    /** Orgs (app-ids) the token can see. */
    @GetMapping("/snyk/orgs")
    public List<SnykOrg> orgs() {
        return snyk.orgs();
    }

    /** Repositories (targets) under an org — the repos a user can watch. */
    @GetMapping("/snyk/orgs/{orgId}/repos")
    public List<SnykTarget> repos(@PathVariable String orgId) {
        return snyk.repos(orgId);
    }

    /** Watched repos with their latest severity counts — backs the Snyk dashboard. */
    @GetMapping("/snyk/watches")
    public List<SnykWatchView> watches() {
        return snyk.watchViews();
    }

    @PostMapping("/snyk/watches")
    @ResponseStatus(HttpStatus.CREATED)
    public SnykWatchView addWatch(@RequestBody SnykWatchRequest req) {
        return snyk.addWatch(req.orgId(), req.orgSlug(), req.orgName(), req.targetId(), req.repoSlug());
    }

    /** Watch an app-id — auto-targets its {@code application-tests} repo (the app-id-centric flow). */
    @PostMapping("/snyk/watches/by-app")
    @ResponseStatus(HttpStatus.CREATED)
    public SnykWatchView addWatchByApp(@RequestBody SnykAppWatchRequest req) {
        return snyk.addWatchForApp(req.orgId(), req.orgSlug(), req.orgName());
    }

    @DeleteMapping("/snyk/watches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeWatch(@PathVariable String id) {
        snyk.removeWatch(id);
    }

    /** The latest snapshot's vulnerabilities for a watch, most-severe first. */
    @GetMapping("/snyk/watches/{id}/issues")
    public List<SnykIssueView> issues(@PathVariable String id) {
        return snyk.latestIssues(id);
    }

    /** Poll every enabled watch now (manual "refresh"). */
    @PostMapping("/snyk/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Integer> refresh() {
        return Map.of("polled", snyk.refreshAll());
    }

    @PostMapping("/snyk/watches/{id}/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void refreshOne(@PathVariable String id) {
        snyk.refresh(id);
    }

    /** The alert feed; {@code unseenOnly=true} for the notification bell. */
    @GetMapping("/snyk/alerts")
    public List<SnykAlertView> alerts(@RequestParam(required = false, defaultValue = "false") boolean unseenOnly) {
        return snyk.alerts(unseenOnly);
    }

    @PostMapping("/snyk/alerts/{id}/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markSeen(@PathVariable String id) {
        snyk.markSeen(id);
    }

    public record SnykWatchRequest(String orgId, String orgSlug, String orgName, String targetId, String repoSlug) {}

    public record SnykAppWatchRequest(String orgId, String orgSlug, String orgName) {}
}
