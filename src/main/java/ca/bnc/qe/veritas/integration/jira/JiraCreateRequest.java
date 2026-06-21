package ca.bnc.qe.veritas.integration.jira;

import java.util.List;

/** Fields to create a Jira issue (description rendered as ADF from the paragraphs). */
public record JiraCreateRequest(
        String projectKey,
        String issueType,
        String summary,
        List<String> descriptionParagraphs,
        List<String> labels
) {}
