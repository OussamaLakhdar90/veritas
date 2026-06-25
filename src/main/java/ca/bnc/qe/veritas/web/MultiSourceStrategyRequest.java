package ca.bnc.qe.veritas.web;

import java.util.List;

/**
 * Request to generate a multi-source test strategy. Any subset of the three sources may be supplied (code-only,
 * Jira-only, pre-dev Jira+Confluence, or all three). The code arm names the repo to clone + extract; Jira a JQL;
 * Confluence the page ids.
 */
public record MultiSourceStrategyRequest(Code code, Jira jira, Confluence confluence) {

    /** Either a local {@code repoPath}, or an {@code appId}+{@code repoSlug} (+optional {@code branch}) to clone. */
    public record Code(String appId, String repoSlug, String branch, String repoPath) {
        public boolean selected() {
            return (repoPath != null && !repoPath.isBlank())
                    || (appId != null && !appId.isBlank() && repoSlug != null && !repoSlug.isBlank());
        }
    }

    public record Jira(String jql, Integer maxResults) {}

    public record Confluence(List<String> pageIds) {}
}
