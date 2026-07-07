package ca.bnc.qe.veritas.snyk.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import ca.bnc.qe.veritas.integration.jira.JiraTransition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The Jira side of a Snyk fix: use a provided ticket or create one carrying the facts (app-ids, dependency, target
 * version, risk, Snyk severity), and drive it through In Progress → In Review → Done. Transitions are robust — they
 * match the project's real workflow by name and skip-with-note if a state is missing, so a fix never fails because
 * of a Jira workflow quirk. The ticket text is deterministic (the facts are known); Copilot isn't required here.
 */
@Service
@Slf4j
public class SnykFixJiraService {

    /**
     * A lifecycle phase + the workflow keywords it matches. {@code positive} keywords identify the target; the
     * shared {@code NEGATIVE} keywords veto a decline/back/cancel transition (so a "Close as Won't Do" isn't mistaken
     * for a genuine Done). Matching prefers a transition's <b>destination status</b> over its label.
     */
    public enum Phase {
        IN_PROGRESS("in progress", "progress", "start", "develop", "implement"),
        IN_REVIEW("in review", "review", "verify", "qa", "ready for", "testing", "code review"),
        DONE("done", "closed", "resolve", "complete", "close", "fixed");

        /** Keywords that mean "not a genuine forward transition" — a decline/cancel/back-out. */
        private static final String[] NEGATIVE = {
                "won't", "wont", "cancel", "reject", "abandon", "decline", "reopen", "back to", "stop"};

        private final String[] positive;

        Phase(String... positive) {
            this.positive = positive;
        }

        private static boolean containsAny(String text, String[] keywords) {
            String n = text == null ? "" : text.toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (n.contains(k)) {
                    return true;
                }
            }
            return false;
        }

        boolean isDecline(String transitionName) {
            return containsAny(transitionName, NEGATIVE);
        }

        /** 2 if the transition's destination status matches, 1 if only its label matches, 0 otherwise. */
        int score(JiraTransition t) {
            if (containsAny(t.toStatus(), positive)) {
                return 2;
            }
            return containsAny(t.name(), positive) ? 1 : 0;
        }
    }

    private final JiraClient jira;

    public SnykFixJiraService(JiraClient jira) {
        this.jira = jira;
    }

    /** Use the provided key, or create a new ticket in {@code project} carrying the fix facts. Returns the key. */
    public String ensureTicket(SnykFixTrain train, String provideKey, String project, String issueType,
                               BreakingVerdict verdict) {
        if (provideKey != null && !provideKey.isBlank()) {
            return provideKey.trim();
        }
        String title = "Snyk fix: bump " + artifact(train.getCoordinate()) + " to " + train.getFixedIn()
                + " (" + upper(train.getSeverity()) + ")";
        return jira.createIssue(new JiraCreateRequest(project, issueType == null || issueType.isBlank() ? "Task" : issueType,
                title, description(train, verdict), List.of("snyk", "dependency-security")));
    }

    /** The ticket's summary/title — best-effort (null on a blank key or a failed fetch), shown in each PR body. */
    public String summary(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            JiraIssue issue = jira.getIssue(key);
            return issue == null ? null : issue.summary();
        } catch (RuntimeException e) {
            log.debug("Could not fetch the Jira summary for {} (non-fatal): {}", key, e.getMessage());
            return null;
        }
    }

    /** Move the ticket to a lifecycle phase, matching the project's real transitions; never throws. */
    public void transitionTo(String jiraKey, Phase phase) {
        if (jiraKey == null || jiraKey.isBlank()) {
            return;
        }
        try {
            // Prefer the transition whose DESTINATION status matches the phase; skip declines/back-outs; pick the
            // best-scoring candidate rather than the first name-substring hit.
            JiraTransition best = null;
            int bestScore = 0;
            for (JiraTransition t : jira.listTransitions(jiraKey)) {
                if (phase.isDecline(t.name())) {
                    continue;
                }
                int s = phase.score(t);
                if (s > bestScore) {
                    bestScore = s;
                    best = t;
                }
            }
            if (best != null) {
                jira.transition(jiraKey, best.id());
                log.info("Jira {} → {} (transition '{}' → {})", jiraKey, phase, best.name(), best.toStatus());
            } else {
                log.info("Jira {} has no '{}' transition available; leaving status unchanged.", jiraKey, phase);
            }
        } catch (RuntimeException e) {
            log.warn("Jira transition to {} failed for {} (non-fatal): {}", phase, jiraKey, e.getMessage());
        }
    }

    /** The deterministic ticket body — every fact the reviewer needs. */
    List<String> description(SnykFixTrain train, BreakingVerdict verdict) {
        List<String> p = new ArrayList<>();
        p.add("Automated dependency security fix raised by Veritas from a Snyk finding.");
        p.add("Snyk severity: " + upper(train.getSeverity()));
        p.add("Dependency: " + train.getCoordinate() + "  (" + train.getOldVersion() + " -> " + train.getFixedIn() + ")");
        p.add("Applications (app-ids): " + (train.getAppIds() == null || train.getAppIds().isBlank()
                ? "(none selected)" : train.getAppIds()));
        p.add("Cascade: the lsist framework repos (bom -> core -> api/web) are bumped, then each selected app's "
                + "application-tests repo picks up the new version.");
        p.add(riskLine(verdict));
        return p;
    }

    private String riskLine(BreakingVerdict verdict) {
        if (verdict == null || !verdict.available()) {
            return "Risk: breaking-change assessment unavailable — the local test build is the gate.";
        }
        if (verdict.breaking()) {
            String notes = verdict.migrationNotes() == null || verdict.migrationNotes().isBlank()
                    ? "" : " Migration: " + verdict.migrationNotes();
            return "Risk: BREAKING change likely (confidence " + verdict.confidence() + "%)."
                    + (verdict.reasons().isEmpty() ? "" : " " + String.join("; ", verdict.reasons())) + notes;
        }
        return "Risk: no breaking change expected (confidence " + verdict.confidence() + "%, advisory).";
    }

    private static String artifact(String coordinate) {
        if (coordinate == null) {
            return "dependency";
        }
        int colon = coordinate.indexOf(':');
        return colon >= 0 ? coordinate.substring(colon + 1) : coordinate;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
