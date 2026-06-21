package ca.bnc.qe.veritas.integration.jira;

/**
 * An issue's workflow status. {@code categoryKey} is Jira's stable status-category key
 * ({@code new} | {@code indeterminate} | {@code done}) — language-independent, unlike {@code name}.
 */
public record JiraStatus(String name, String categoryKey) {}
