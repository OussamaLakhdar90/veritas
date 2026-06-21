package ca.bnc.qe.veritas.integration.jira;

import com.fasterxml.jackson.databind.JsonNode;

/** A fetched Jira issue. {@code description} is the raw ADF document (Jira Cloud), normalized downstream. */
public record JiraIssue(String key, String summary, JsonNode description) {}
