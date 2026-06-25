package ca.bnc.qe.veritas.evidence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.engine.model.ApiModel;

/**
 * Which sources a run draws on, and their parameters. Any subset is valid — code-only, Jira-only, the pre-dev
 * Jira+Confluence case, or all three (design §1.1). The {@code code} model is supplied already-extracted (the
 * caller runs {@code JavaSpringExtractor}), keeping the {@link EvidenceExtractor} decoupled from cloning.
 */
public record SourceSelection(ApiModel code, String jql, int maxResults, List<String> pageIds) {

    public SourceSelection {
        pageIds = pageIds == null ? List.of() : List.copyOf(pageIds);
    }

    public boolean hasCode() {
        return code != null;
    }

    public boolean hasJira() {
        return jql != null && !jql.isBlank();
    }

    public boolean hasConfluence() {
        return !pageIds.isEmpty();
    }

    /** The source kinds the user actually selected (drives the §1.3 hard-fail gate; never includes POLICY). */
    public Set<SourceKind> selected() {
        Set<SourceKind> s = new LinkedHashSet<>();
        if (hasCode()) {
            s.add(SourceKind.CODE);
        }
        if (hasJira()) {
            s.add(SourceKind.JIRA);
        }
        if (hasConfluence()) {
            s.add(SourceKind.CONFLUENCE);
        }
        return s;
    }

    public static SourceSelection ofCode(ApiModel code) {
        return new SourceSelection(code, null, 0, List.of());
    }

    public static SourceSelection ofJira(String jql, int maxResults) {
        return new SourceSelection(null, jql, maxResults, List.of());
    }

    public static SourceSelection ofConfluence(List<String> pageIds) {
        return new SourceSelection(null, null, 0, pageIds);
    }

    /** The pre-dev case: intent (Jira) + rationale (Confluence), no code yet (design §1.1). */
    public static SourceSelection ofJiraAndConfluence(String jql, int maxResults, List<String> pageIds) {
        return new SourceSelection(null, jql, maxResults, pageIds);
    }
}
