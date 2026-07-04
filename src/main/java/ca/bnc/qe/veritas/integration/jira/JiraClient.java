package ca.bnc.qe.veritas.integration.jira;

import java.util.List;

/** Jira access. Core read/write is required; the enrichment methods have safe defaults for clients that
 *  don't implement them (e.g. the Cloud client). The Server/DC client implements them all. */
public interface JiraClient {
    List<JiraIssue> search(String jql, List<String> fields, int maxResults);
    JiraIssue getIssue(String key);

    /** Create an issue; returns the new issue key. */
    String createIssue(JiraCreateRequest request);

    /** Current workflow status (name + stable category key) of an issue, for defect status sync. */
    JiraStatus getStatus(String key);

    /** The workflow transitions available from the issue's current status. Empty if unsupported. */
    default List<JiraTransition> listTransitions(String key) {
        return List.of();
    }

    /** Move an issue through a workflow transition (by its transition id). */
    default void transition(String key, String transitionId) {
        throw new UnsupportedOperationException("transition not supported by this Jira client");
    }

    /** Add a (wiki-markup) comment to an issue — e.g. a correction-notification when a fix changes. */
    default void addComment(String key, String wikiBody) {
        throw new UnsupportedOperationException("addComment not supported by this Jira client");
    }

    /** Attach a text file (e.g. the corrected OpenAPI YAML) to an issue. */
    default void attachFile(String key, String fileName, String content) {
        throw new UnsupportedOperationException("attachFile not supported by this Jira client");
    }

    /** Discover which fields the Create screen allows for a project+issuetype (and custom-field keys). */
    default CreateMeta createMeta(String projectKey, String issueType) {
        return CreateMeta.empty();
    }

    /** Projects the caller can file into (key + display name), for a picker. Empty if unsupported. */
    default List<JiraProject> listProjects() {
        return List.of();
    }

    /**
     * Open epics in a project (most-recently-updated first), for the epic picker. Edition-agnostic — an epic is
     * {@code issuetype = Epic} on both Cloud and Server/DC (only linking a CHILD to an epic differs by edition).
     * The project key is validated ({@link JiraKeys#projectKey}) before it reaches the JQL, so it can't inject.
     */
    default List<JiraIssue> listEpics(String projectKey, int max) {
        String jql = "project = \"" + JiraKeys.projectKey(projectKey) + "\""
                + " AND issuetype = Epic AND statusCategory != Done ORDER BY updated DESC";
        return search(jql, List.of("summary", "status"), max);
    }

    /** Project versions (fixVersions) — to resolve/validate a release link. Empty if unsupported/none. */
    default List<JiraVersion> listVersions(String projectKey) {
        return List.of();
    }

    /** Cheap authenticated identity probe (`/myself`) for Test Connection; returns the account name or throws. */
    default String whoAmI() {
        throw new UnsupportedOperationException("whoAmI not supported by this Jira client");
    }
}
