package ca.bnc.qe.veritas.evidence;

import java.util.List;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import ca.bnc.qe.veritas.integration.jira.EpicChildrenJql;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the two convenience source inputs — a Jira <b>epic key</b> and a Confluence <b>root page id</b> — into the
 * primitives the evidence pipeline already understands (a JQL, a list of page ids), deterministically and at $0
 * (design doc #17 Phase 6). Keeping the expansion here means the extractor / adapters / gap engine / §6 preview all
 * stay unchanged: an epic expands to the same child-issue JQL the {@code search} path runs, and a root page expands
 * to the descendant page ids the existing Confluence adapter fetches.
 */
@Component
@Slf4j
public class SourceExpander {

    /** Hard cap on a Confluence page-tree expansion, so a huge tree can't blow up a single preview. */
    public static final int MAX_TREE_PAGES = 500;

    private final JiraClient jira;
    private final ConfluenceClient confluence;
    private final ConnectionsProperties connections;

    public SourceExpander(JiraClient jira, ConfluenceClient confluence, ConnectionsProperties connections) {
        this.jira = jira;
        this.confluence = confluence;
        this.connections = connections;
    }

    /**
     * The JQL selecting an epic's child issues, edition-aware. On Server/DC the "Epic Link" custom-field id is
     * discovered via create-meta (best-effort — a discovery failure falls back to the field name). Throws
     * {@link IllegalArgumentException} (→ 400) for a malformed epic key.
     */
    public String jqlForEpic(String epicKey) {
        if (epicKey == null || epicKey.isBlank()) {
            throw new IllegalArgumentException("An epic key is required to expand Jira children.");
        }
        String edition = connections.getJira().getEdition();
        String epicLinkFieldKey = null;
        if ("SERVER_DC".equalsIgnoreCase(edition)) {
            try {
                epicLinkFieldKey = jira.createMeta(projectOf(epicKey), "Story").epicLinkFieldKey();
            } catch (RuntimeException e) {
                log.warn("Couldn't discover the Epic Link field for {} — falling back to the field name: {}",
                        epicKey, e.getMessage());
            }
        }
        return EpicChildrenJql.forEpic(edition, epicKey, epicLinkFieldKey);
    }

    /**
     * The page ids of the tree rooted at {@code rootPageId} (root + descendants). Degrades to an empty list on a
     * fetch failure (logged) — so the §1.3 hard-fail gate surfaces an empty Confluence arm rather than the whole
     * preview aborting, consistent with how the adapters fail soft.
     */
    public List<String> pageIdsForRoot(String rootPageId) {
        if (rootPageId == null || rootPageId.isBlank()) {
            return List.of();
        }
        try {
            return confluence.descendants(rootPageId, MAX_TREE_PAGES).stream().map(ConfluencePage::id).toList();
        } catch (RuntimeException e) {
            log.warn("Confluence page-tree expansion failed for {}: {}", rootPageId, e.getMessage());
            return List.of();
        }
    }

    /** The project key prefix of an issue key (CIAM-100 → CIAM), for the Server epic-link create-meta lookup. */
    private static String projectOf(String epicKey) {
        int dash = epicKey.indexOf('-');
        return dash > 0 ? epicKey.substring(0, dash) : epicKey;
    }
}
