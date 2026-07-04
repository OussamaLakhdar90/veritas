package ca.bnc.qe.veritas.integration.jira;

import java.util.List;

/**
 * Fields to create a Jira issue (description rendered as ADF on Cloud, wiki markup on Server/DC).
 *
 * @param parentEpicKey optional epic to file the new issue under — {@code null} (the common case) creates a
 *                      free-standing issue. When set, the edition client links it: the "Epic Link" custom field on
 *                      Server/DC, the {@code parent} field on Cloud.
 */
public record JiraCreateRequest(
        String projectKey,
        String issueType,
        String summary,
        List<String> descriptionParagraphs,
        List<String> labels,
        String parentEpicKey
) {
    /** A request with no epic parent — the common case, and back-compatible with every existing call site. */
    public JiraCreateRequest(String projectKey, String issueType, String summary,
                             List<String> descriptionParagraphs, List<String> labels) {
        this(projectKey, issueType, summary, descriptionParagraphs, labels, null);
    }
}
