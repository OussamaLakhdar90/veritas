package ca.bnc.qe.veritas.integration.jira;

/** A Jira project version (fixVersion) — used to resolve/validate a release. */
public record JiraVersion(String id, String name, boolean released, boolean archived) {}
