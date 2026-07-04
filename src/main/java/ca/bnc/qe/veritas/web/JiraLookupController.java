package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Map;
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

    private final JiraClient jira;

    public JiraLookupController(JiraClient jira) {
        this.jira = jira;
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

    /** An epic the user can pick — its key + summary. */
    public record EpicOption(String key, String summary) {}

    /** Body for creating an epic (projectKey + summary required, description optional). */
    public record CreateEpicRequest(String projectKey, String summary, String description) {}
}
