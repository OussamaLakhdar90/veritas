package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.jira.EpicChildrenJql;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraCreateRequest;
import ca.bnc.qe.veritas.integration.jira.JiraProject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Jira lookups for the pickers the Snyk bulk-fix flow needs: list the projects the user can file into, list the open
 * epics in a project, and create an epic to group a batch of fix tickets under. Thin pass-throughs to the active
 * edition {@link JiraClient} (Server/DC or Cloud). A missing Jira token surfaces as the shared 422
 * {@code secret-required} (handled globally), so the UI can deep-link to Settings rather than show a raw error.
 */
@RestController
@RequestMapping("/api/v1/jira")
public class JiraLookupController {

    /** Cap the epic picker at a sensible page — the user picks one, they don't scroll thousands. */
    private static final int EPIC_LIMIT = 100;
    /** Cap the story picker under an epic — the open children are few; the user picks one. */
    private static final int STORY_LIMIT = 100;

    private final JiraClient jira;
    private final ConnectionsProperties connections;

    public JiraLookupController(JiraClient jira, ConnectionsProperties connections) {
        this.jira = jira;
        this.connections = connections;
    }

    /** Projects the user can file into (key + display name). */
    @GetMapping("/projects")
    public List<JiraProject> projects() {
        return jira.listProjects();
    }

    /** Open epics in a project (most-recently-updated first), for the epic picker. */
    @GetMapping("/projects/{key}/epics")
    public List<EpicOption> epics(@PathVariable String key) {
        return jira.listEpics(key, EPIC_LIMIT).stream()
                .map(i -> new EpicOption(i.key(), i.summary()))
                .toList();
    }

    /**
     * Open child stories under an epic (most-recently-updated first), for the story picker — the bulk-fix flow files
     * every app's fix under ONE selected (or newly created) story that lives inside the chosen epic. Children are
     * matched edition-aware ({@code parent} on Cloud, the "Epic Link" custom field on Server/DC — its numeric id
     * discovered via create-meta, falling back to the field name) and filtered to open ({@code statusCategory != Done}).
     */
    @GetMapping("/epics/{epicKey}/stories")
    public List<StoryOption> epicStories(@PathVariable String epicKey) {
        String edition = connections.getJira().getEdition();
        String epicLinkFieldKey = null;
        if ("SERVER_DC".equalsIgnoreCase(edition)) {
            try {
                epicLinkFieldKey = jira.createMeta(projectOf(epicKey), "Story").epicLinkFieldKey();
            } catch (RuntimeException e) {
                // create-meta unavailable — EpicChildrenJql falls back to the "Epic Link" field name.
            }
        }
        String jql = EpicChildrenJql.forEpic(edition, epicKey, epicLinkFieldKey)
                + " AND statusCategory != Done ORDER BY updated DESC";
        return jira.search(jql, List.of("summary", "status"), STORY_LIMIT).stream()
                .map(i -> new StoryOption(i.key(), i.summary()))
                .toList();
    }

    /** Create a new epic to group a batch of fix tickets; returns its key. */
    @PostMapping("/epics")
    public Map<String, String> createEpic(@RequestBody CreateEpicRequest req) {
        if (req == null || isBlank(req.projectKey()) || isBlank(req.summary())) {
            throw new IllegalArgumentException("projectKey and summary are required to create an epic.");
        }
        List<String> paragraphs = isBlank(req.description()) ? List.of() : List.of(req.description().trim());
        String epicKey = jira.createIssue(new JiraCreateRequest(
                req.projectKey().trim(), "Epic", req.summary().trim(), paragraphs,
                List.of("veritas", "dependency-security")));
        return Map.of("key", epicKey);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** The project key an issue key belongs to (e.g. {@code CIAM-100} → {@code CIAM}), for create-meta lookups. */
    private static String projectOf(String issueKey) {
        int dash = issueKey == null ? -1 : issueKey.lastIndexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : (issueKey == null ? "" : issueKey);
    }

    /** An epic the user can pick — its key + summary. */
    public record EpicOption(String key, String summary) {}

    /** An open story under an epic the user can pick to file fixes under — its key + summary. */
    public record StoryOption(String key, String summary) {}

    /** Body for creating an epic (projectKey + summary required, description optional). */
    public record CreateEpicRequest(String projectKey, String summary, String description) {}
}
