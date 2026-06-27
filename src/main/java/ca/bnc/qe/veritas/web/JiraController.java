package ca.bnc.qe.veritas.web;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Jira lookup for pickers — backs the "select a Jira ticket" search in the test-gen wizard. A query that contains an
 * issue key (typed or pasted from a browse URL) is resolved exactly; anything else is a summary text search. Returns a
 * lightweight {@code key + summary} list, capped, ordered most-recently-updated first.
 */
@RestController
@RequestMapping("/api/v1")
public class JiraController {

    /** Issue key anywhere in the input — also pulls the key out of a pasted ".../browse/CIAM-1842" URL. */
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]+-\\d+");

    private final JiraClient jira;

    public JiraController(JiraClient jira) {
        this.jira = jira;
    }

    @GetMapping("/jira/search")
    public List<IssueRef> search(@RequestParam String q) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return List.of();
        }
        Matcher m = KEY.matcher(query.toUpperCase(Locale.ROOT));
        String jql;
        if (m.find()) {
            jql = "key = \"" + m.group() + "\"";   // a pasted key or browse URL → exact lookup
        } else {
            String safe = query.replaceAll("[\"\\\\]", " ").trim();   // strip JQL-breaking chars
            jql = "summary ~ \"" + safe + "\" ORDER BY updated DESC";
        }
        return jira.search(jql, List.of("summary"), 10).stream()
                .map(i -> new IssueRef(i.key(), i.summary()))
                .toList();
    }

    public record IssueRef(String key, String summary) {}
}
