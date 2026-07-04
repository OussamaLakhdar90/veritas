package ca.bnc.qe.veritas.integration.jira;

/** A Jira project the user can file into — {@code key} (e.g. {@code CIAM}) plus its display {@code name}. For the picker. */
public record JiraProject(String key, String name) {}
