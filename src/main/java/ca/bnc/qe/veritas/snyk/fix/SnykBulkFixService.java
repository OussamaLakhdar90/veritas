package ca.bnc.qe.veritas.snyk.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixRequest.AppSelection;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixRequest.IssueSelection;
import ca.bnc.qe.veritas.snyk.fix.SnykBulkFixResult.AppResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a "Fix vulnerabilities" batch into the shape the user chose: one epic, one Jira ticket per affected
 * application (filed under that epic), and one fix train per selected vulnerability — each train linked to its
 * application's ticket so a batch of dozens of fixes produces a handful of app tickets, not dozens.
 *
 * <p>It composes existing pieces only: {@link JiraClient#createIssue} (with an epic parent, added for this flow) for
 * the epic + app tickets, and {@link AsyncSnykFixRunner#submit} for each train. A train started with a Jira key
 * reuses that ticket rather than creating its own, which is how every fix under one app lands on one ticket.
 * Validation is fail-fast (before any Jira write); one app failing to launch is isolated, never fatal to the batch.
 */
@Service
@Slf4j
public class SnykBulkFixService {

    private final JiraClient jira;
    private final AsyncSnykFixRunner runner;

    public SnykBulkFixService(JiraClient jira, AsyncSnykFixRunner runner) {
        this.jira = jira;
        this.runner = runner;
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

        String epicKey = resolveEpic(req);
        String project = req.project().trim();
        List<String> reviewers = req.reviewers() == null ? List.of() : req.reviewers();

        List<AppResult> results = new ArrayList<>();
        for (AppSelection app : apps) {
            if (!hasIssues(app)) {
                continue;
            }
            try {
                // One ticket per app, filed under the epic (the epic link is applied by the edition client).
                String appTicket = jira.createIssue(new JiraCreateRequest(project, "Task",
                        "Dependency security fixes — " + app.appId(), appDescription(app),
                        List.of("snyk", "dependency-security"), epicKey));
                List<String> trainIds = new ArrayList<>();
                for (IssueSelection issue : app.issues()) {
                    // jiraKey = the app ticket → the train reuses it instead of creating its own; autoConfirm=true
                    // runs each train straight through (the reactor build is the gate; breaking ones hold for review).
                    String trainId = runner.submit(new SnykFixRequest(app.watchId(), issue.issueId(),
                            issue.coordinate(), issue.oldVersion(), issue.fixedIn(), issue.severity(),
                            List.of(app.appId()), appTicket, project, null, reviewers, null, true));
                    trainIds.add(trainId);
                }
                results.add(new AppResult(app.appId(), appTicket, trainIds, null));
            } catch (RuntimeException e) {
                // Isolate a single app's failure (e.g. its ticket couldn't be created) — the rest of the batch runs.
                log.warn("Bulk fix: application {} could not be launched (non-fatal): {}", app.appId(), e.getMessage());
                results.add(new AppResult(app.appId(), null, List.of(), e.getMessage()));
            }
        }
        return new SnykBulkFixResult(epicKey, results);
    }

    /** Use the provided epic, or create one in the project; refuse to proceed without one (epic is required). */
    private String resolveEpic(SnykBulkFixRequest req) {
        if (!isBlank(req.epicKey())) {
            return req.epicKey().trim();
        }
        if (req.createEpic()) {
            String summary = isBlank(req.epicSummary()) ? "Dependency security remediation" : req.epicSummary().trim();
            return jira.createIssue(new JiraCreateRequest(req.project().trim(), "Epic", summary,
                    List.of("Batch of Snyk dependency-security fixes raised by Veritas."),
                    List.of("veritas", "dependency-security")));
        }
        throw new IllegalArgumentException("An epic is required — select an existing epic or ask to create one.");
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

    /** The per-app ticket body: which vulnerabilities this app's fix covers, deterministically. */
    private List<String> appDescription(AppSelection app) {
        List<String> p = new ArrayList<>();
        p.add("Automated dependency security fixes for " + app.appId() + ", raised by Veritas from Snyk findings.");
        p.add("Fixes in this ticket:");
        for (IssueSelection i : app.issues()) {
            p.add("- " + i.coordinate() + "  (" + i.oldVersion() + " -> " + i.fixedIn() + ", "
                    + upper(i.severity()) + ")");
        }
        p.add("The lsist framework repos (bom -> core -> api/web) are bumped first, then this app's application-tests "
                + "repo picks up the new version. Breaking changes are held for review — Veritas never auto-merges.");
        return p;
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
