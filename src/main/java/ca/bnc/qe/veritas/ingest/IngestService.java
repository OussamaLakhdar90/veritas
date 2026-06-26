package ca.bnc.qe.veritas.ingest;

import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.evidence.Redactor;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import org.springframework.stereotype.Service;

/**
 * Builds a compact, citable {@link TestBasis} from Jira and/or Confluence — fetch (deterministic) →
 * normalize (ADF/XHTML → markdown) → extract (test-basis items) → <b>redact</b>. Developer-pasted tickets and
 * Confluence pages routinely carry PII/secrets; this legacy basis text is fed verbatim to the LLM by
 * {@code BasisBuilder.fromIngest}, so — exactly like the evidence adapters — every item's text passes through
 * {@link Redactor} here, the one choke-point before the basis leaves this service. The LLM never sees raw payloads.
 */
@Service
public class IngestService {

    private static final List<String> JIRA_FIELDS = List.of("summary", "description");

    private final JiraClient jira;
    private final ConfluenceClient confluence;
    private final AdfToMarkdown adf;
    private final ConfluenceStorageToMarkdown storage;
    private final TestBasisExtractor extractor;

    public IngestService(JiraClient jira, ConfluenceClient confluence, AdfToMarkdown adf,
                         ConfluenceStorageToMarkdown storage, TestBasisExtractor extractor) {
        this.jira = jira;
        this.confluence = confluence;
        this.adf = adf;
        this.storage = storage;
        this.extractor = extractor;
    }

    public TestBasis fromJira(String jql, int maxResults) {
        List<TestBasisItem> items = new ArrayList<>();
        for (JiraIssue issue : jira.search(jql, JIRA_FIELDS, maxResults)) {
            // the summary itself is a requirement (so issues with no AC still contribute, with traceability)
            items.add(new TestBasisItem(issue.key() + "#summary", issue.key(), TestBasisKind.REQUIREMENT, issue.summary()));
            String md = issue.description() == null ? "" : adf.toMarkdown(issue.description());
            items.addAll(extractor.extract(new NormalizedDoc("jira", issue.key(), issue.summary(), md)));
        }
        return new TestBasis(redactAll(items));
    }

    public TestBasis fromConfluence(List<String> pageIds) {
        List<TestBasisItem> items = new ArrayList<>();
        for (String pageId : pageIds) {
            ConfluencePage page = confluence.getPage(pageId);
            NormalizedDoc doc = storage.normalize(page.id(), page.title(), page.storageXhtml());
            items.addAll(extractor.extract(doc));
        }
        return new TestBasis(redactAll(items));
    }

    /**
     * Redact PII/secrets from each item's text before it can reach the LLM (mirrors the evidence adapters). The
     * id/origin/kind are structural (issue keys / page ids), never free text, so only {@code text} is scrubbed.
     */
    private List<TestBasisItem> redactAll(List<TestBasisItem> items) {
        List<TestBasisItem> out = new ArrayList<>(items.size());
        for (TestBasisItem it : items) {
            out.add(new TestBasisItem(it.id(), it.origin(), it.kind(), Redactor.redact(it.text()).text()));
        }
        return out;
    }

    public TestBasis assemble(String jql, List<String> pageIds, int maxResults) {
        List<TestBasisItem> items = new ArrayList<>();
        if (jql != null && !jql.isBlank()) {
            items.addAll(fromJira(jql, maxResults).items());
        }
        if (pageIds != null && !pageIds.isEmpty()) {
            items.addAll(fromConfluence(pageIds).items());
        }
        return new TestBasis(items);
    }
}
