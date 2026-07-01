package ca.bnc.qe.veritas.integration.jira;

/** An available workflow transition for an issue: the id to POST and the display name to match on. */
public record JiraTransition(String id, String name) {
}
