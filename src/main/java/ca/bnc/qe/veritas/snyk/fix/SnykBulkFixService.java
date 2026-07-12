package ca.bnc.qe.veritas.snyk.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraLinks;
import ca.bnc.qe.veritas.integration.jira.JiraProject;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixRequest.AppSelection;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixRequest.IssueSelection;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixResult.AppResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a "Fix vulnerabilities" batch into the shape the user chose: one epic, one <em>shared story</em> under
 * that epic for the whole batch, and one fix train per selected vulnerability — every train reuses the single shared
 * story key, so a batch of dozens of fixes produces one epic + one story, not dozens of tickets.
 *
 * <p>It composes existing pieces only: {@link JiraClient#createIssue} (with an epic parent, added for this flow) for
 * the epic + shared story, and {@link AsyncSnykFixRunner#submit} for each train. A train started with a Jira key
 * reuses that ticket rather than creating its own — which is also why every train for one application resolves to the
 * <em>same</em> fix branch ({@code branchName(storyKey, bitbucketProject)}); those same-branch pushes accumulate
 * rather than clobber (see {@code PrPublisher.pushBranch}'s per-branch serialization). Validation is fail-fast (before
 * any Jira write); one app failing to launch is isolated, never fatal to the batch.
 */
@Service
@Slf4j
public class SnykBulkFixService {

    private final JiraClient jira;
    private final AsyncSnykFixRunner runner;
    private final ConnectionsProperties connections;

    public SnykBulkFixService(JiraClient jira, AsyncSnykFixRunner runner, ConnectionsProperties connections) {
        this.jira = jira;
        this.runner = runner;
        this.connections = connections;
    }

    public SnykBulkFixResult launch(SnykBulkFixRequest req) {
        if (req == null || isBlank(req.project())) {
            throw new IllegalArgumentException("A Jira project is required to file the fix tickets.");
        }
        List<AppSelection> apps = req.apps() == null ? List.of() : req.apps();
        if (apps.stream().noneMatch(SnykBulkFixService::hasIssues)) {
            throw new IllegalArgumentException("Select at least one vulnerability to fix.");
        }
        // Fail fast on unsafe Maven tokens BEFORE any Jira write — the same guard the single-issue endpoint applies,
        // so a bad coordinate can never reach the pom editor (and never leaves a half-created epic behind).
        validateTokens(apps);

        // Validate the Jira project BEFORE creating any epic/ticket or cloning any repo — this is what stops the old
        // "clone first, then fail on a bad project" behaviour and the cryptic 400. It also exercises the Jira token,
        // so a missing token surfaces here as a clean connection error, not a half-run batch.
        String project = resolveProjectKey(req.project().trim());
        String epicKey = resolveEpic(req, project);
        // One shared story under the epic — an existing OPEN one the user chose, or a new one. Created BEFORE any train
        // starts, so a bad story destination fails the batch fast (never a half-run with orphaned trains).
        String storyKey = resolveStory(req, project, epicKey, apps);
        List<String> reviewers = req.reviewers() == null ? List.of() : req.reviewers();

        // Clickable Jira links for the confirmation UI (null when no base URL is configured — never a broken link).
        String jiraBaseUrl = connections.getJira().getBaseUrl();
        String epicUrl = JiraLinks.browseUrl(jiraBaseUrl, epicKey);
        String storyUrl = JiraLinks.browseUrl(jiraBaseUrl, storyKey);

        List<AppResult> results = new ArrayList<>();
        for (AppSelection app : apps) {
            if (!hasIssues(app)) {
                continue;
            }
            try {
                List<String> trainIds = new ArrayList<>();
                for (IssueSelection issue : app.issues()) {
                    // jiraKey = the shared story → every train reuses it instead of creating its own; autoConfirm=true
                    // runs each train straight through (the reactor build is the gate; breaking ones hold for review).
                    String trainId = runner.submit(new SnykFixRequest(app.watchId(), issue.issueId(),
                            issue.coordinate(), issue.oldVersion(), issue.fixedIn(), issue.severity(),
                            List.of(app.appId()), storyKey, project, null, reviewers, null, true, storyKey));
                    trainIds.add(trainId);
                }
                results.add(new AppResult(app.appId(), storyKey, storyUrl, trainIds, null));
            } catch (RuntimeException e) {
                // Isolate a single app's failure — the rest of the batch (on the same shared story) still runs.
                log.warn("Bulk fix: application {} could not be launched (non-fatal): {}", app.appId(), e.getMessage());
                results.add(new AppResult(app.appId(), null, null, List.of(), e.getMessage()));
            }
        }
        return new SnykBulkFixResult(epicKey, storyKey, epicUrl, storyUrl, results);
    }

    /**
     * Validate the typed project against the accessible Jira projects and resolve it to its canonical key (so a
     * lowercase or wrong-case entry that maps to a real project still works, and an unknown one fails clearly). A
     * missing/blank Jira token makes {@code listProjects} throw a connection error, surfaced before anything is created.
     */
    private String resolveProjectKey(String typed) {
        return jira.listProjects().stream()
                .filter(p -> typed.equalsIgnoreCase(p.key()))
                .map(JiraProject::key)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Jira project '" + typed + "' wasn't found among your "
                        + "accessible projects — check the project key in Settings (and that your Jira token is set)."));
    }

    /** Use the provided epic, or create one in the (already-validated) project; refuse to proceed without one. */
    private String resolveEpic(SnykBulkFixRequest req, String project) {
        if (!isBlank(req.epicKey())) {
            return req.epicKey().trim();
        }
        if (req.createEpic()) {
            String summary = isBlank(req.epicSummary()) ? "Dependency security remediation" : req.epicSummary().trim();
            return jira.createIssue(new JiraCreateRequest(project, "Epic", summary,
                    List.of("Batch of Snyk dependency-security fixes raised by Veritas."),
                    List.of("veritas", "dependency-security")));
        }
        throw new IllegalArgumentException("An epic is required — select an existing epic or ask to create one.");
    }

    /** Use the chosen existing story, or create one under the epic; refuse to proceed without a story to file under. */
    private String resolveStory(SnykBulkFixRequest req, String project, String epicKey, List<AppSelection> apps) {
        if (!isBlank(req.storyKey())) {
            return req.storyKey().trim();
        }
        if (req.createStory()) {
            String summary = isBlank(req.storySummary()) ? "Dependency security fixes" : req.storySummary().trim();
            return jira.createIssue(new JiraCreateRequest(project, "Story", summary, storyDescription(apps),
                    List.of("veritas", "snyk", "dependency-security"), epicKey));
        }
        throw new IllegalArgumentException(
                "A story is required — select an existing story under the epic or ask to create one.");
    }

    private void validateTokens(List<AppSelection> apps) {
        for (AppSelection app : apps) {
            if (app == null || app.issues() == null) {
                continue;
            }
            for (IssueSelection i : app.issues()) {
                String[] gav = i.coordinate() == null ? new String[0] : i.coordinate().split(":", 2);
                if (gav.length != 2 || !MavenTokens.isSafe(gav[0]) || !MavenTokens.isSafe(gav[1])) {
                    throw new IllegalArgumentException(
                            "coordinate must be groupId:artifactId using only letters, digits, '.', '-', '_': "
                                    + i.coordinate());
                }
                if (!MavenTokens.isSafe(i.fixedIn())) {
                    throw new IllegalArgumentException(
                            "fixedIn must be a plain Maven version (letters, digits, '.', '-', '_'): " + i.fixedIn());
                }
            }
        }
    }

    /**
     * The shared story body — professional plain paragraphs that read well on both Jira editions (Cloud renders one
     * ADF paragraph per string; Server/DC joins with blank lines, so no wiki/ADF markup here). It explains what the
     * story covers, a readable scope summary with counts, the concrete per-application upgrades, how the run
     * proceeds (framework-first, AI-gated), and the human-merge governance.
     */
    private List<String> storyDescription(List<AppSelection> apps) {
        List<AppSelection> withIssues = apps.stream().filter(SnykBulkFixService::hasIssues).toList();
        int appCount = withIssues.size();
        int upgradeCount = withIssues.stream().mapToInt(a -> a.issues().size()).sum();

        List<String> p = new ArrayList<>();
        p.add("This story tracks automated dependency-security fixes that Veritas raised from Snyk findings. Each "
                + "vulnerable dependency is upgraded to its Snyk-recommended safe version and delivered as its own "
                + "pull request, linked back to this story.");
        p.add("Scope: " + count(appCount, "application") + ", " + count(upgradeCount, "dependency upgrade")
                + severityBreakdown(withIssues) + ".");
        for (AppSelection app : withIssues) {
            p.add(appLine(app));
        }
        p.add("How this runs: Veritas bumps the shared framework first (BOM, then core, then api/web), rebuilds it "
                + "and runs each application's tests locally, and only when that whole build is green does it open "
                + "the pull requests in that order under this story. If the AI review flags a breaking change, the "
                + "version-bump branches are still pushed but the pull requests are held for your review before "
                + "anything proceeds.");
        p.add("Governance: Veritas never merges automatically — a human reviewer merges each pull request. The "
                + "assigned reviewers are notified on every pull request.");
        return p;
    }

    /** "1 application" / "2 applications" — a pluralized count for the scope line. */
    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** " (1 critical, 2 high)" from the selected issues, in severity order, omitting empty buckets; "" if none. */
    private static String severityBreakdown(List<AppSelection> apps) {
        int crit = 0;
        int high = 0;
        int med = 0;
        int low = 0;
        for (AppSelection app : apps) {
            for (IssueSelection i : app.issues()) {
                switch (upper(i.severity())) {
                    case "CRITICAL" -> crit++;
                    case "HIGH" -> high++;
                    case "MEDIUM" -> med++;
                    case "LOW" -> low++;
                    default -> { /* an unknown severity isn't bucketed in the breakdown */ }
                }
            }
        }
        List<String> parts = new ArrayList<>();
        addBucket(parts, crit, "critical");
        addBucket(parts, high, "high");
        addBucket(parts, med, "medium");
        addBucket(parts, low, "low");
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private static void addBucket(List<String> parts, int n, String label) {
        if (n > 0) {
            parts.add(n + " " + label);
        }
    }

    /** One readable line per app: "APP7576 (2 upgrades): com.a:x 1.0.0->2.0.0 [critical]; com.b:y 1.2.0->1.3.0 [high]". */
    private static String appLine(AppSelection app) {
        List<String> ups = new ArrayList<>();
        for (IssueSelection i : app.issues()) {
            ups.add(i.coordinate() + " " + i.oldVersion() + "->" + i.fixedIn() + " [" + lower(i.severity()) + "]");
        }
        return app.appId() + " (" + count(app.issues().size(), "upgrade") + "): " + String.join("; ", ups);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean hasIssues(AppSelection app) {
        return app != null && !isBlank(app.appId()) && app.issues() != null && !app.issues().isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
