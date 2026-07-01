package ca.bnc.qe.veritas.snyk.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
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

    /** Which lifecycle phase to move the ticket to; each carries the workflow-name keywords it matches on. */
    public enum Phase {
        IN_PROGRESS("in progress", "progress", "start", "develop"),
        IN_REVIEW("in review", "review"),
        DONE("done", "closed", "resolve", "complete", "close");

        private final String[] keywords;

        Phase(String... keywords) {
            this.keywords = keywords;
        }

        boolean matches(String transitionName) {
            String n = transitionName == null ? "" : transitionName.toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (n.contains(k)) {
                    return true;
                }
            }
            return false;
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

    /** Move the ticket to a lifecycle phase, matching the project's real transitions; never throws. */
    public void transitionTo(String jiraKey, Phase phase) {
        if (jiraKey == null || jiraKey.isBlank()) {
            return;
        }
        try {
            List<JiraTransition> transitions = jira.listTransitions(jiraKey);
            Optional<JiraTransition> match = transitions.stream().filter(t -> phase.matches(t.name())).findFirst();
            if (match.isPresent()) {
                jira.transition(jiraKey, match.get().id());
                log.info("Jira {} → {} (transition '{}')", jiraKey, phase, match.get().name());
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
