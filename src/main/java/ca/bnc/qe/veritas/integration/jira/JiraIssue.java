package ca.bnc.qe.veritas.integration.jira;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A fetched Jira issue. {@code description} is the raw body (ADF document on Cloud, wiki-markup string on
 * Server/DC), normalized downstream. The widened metadata — {@code lifecycle}, {@code priority}, {@code labels},
 * {@code components}, {@code links} — drives the two-axis feature status (§1.2) and the clustering hints; it is
 * populated by the edition clients via {@link JiraFieldParser} and is empty/null for issues fetched without it.
 *
 * @param lifecycle  normalized workflow state ({@code TO_DO|IN_PROGRESS|DONE|DESCOPED}); null when unknown
 * @param priority   priority display name; null when none
 * @param labels     issue labels (clustering hints)
 * @param components project components the issue belongs to (clustering hints)
 * @param links      related issue keys (blocks/relates) — for traceability + status
 */
public record JiraIssue(
        String key,
        String summary,
        JsonNode description,
        String lifecycle,
        String priority,
        List<String> labels,
        List<String> components,
        List<String> links) {

    public JiraIssue {
        labels = labels == null ? List.of() : List.copyOf(labels);
        components = components == null ? List.of() : List.copyOf(components);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** A minimal issue without the widened metadata — for {@code getIssue} and simple fixtures. */
    public static JiraIssue basic(String key, String summary, JsonNode description) {
        return new JiraIssue(key, summary, description, null, null, List.of(), List.of(), List.of());
    }
}
