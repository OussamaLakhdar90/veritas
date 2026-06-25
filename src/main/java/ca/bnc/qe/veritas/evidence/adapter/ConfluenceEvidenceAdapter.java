package ca.bnc.qe.veritas.evidence.adapter;

import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.Hints;
import ca.bnc.qe.veritas.evidence.Redactor;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.ingest.ConfluenceStorageToMarkdown;
import ca.bnc.qe.veritas.ingest.NormalizedDoc;
import ca.bnc.qe.veritas.ingest.TestBasisExtractor;
import ca.bnc.qe.veritas.ingest.TestBasisItem;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns Confluence pages into design/rationale evidence — all units typed {@code DESIGN} (Confluence is
 * authoritative for design & rules, not scope). Each page is fetched in its own try/catch so one 500 doesn't
 * abort the run (design §1.3). Section-anchored, content-hashed ids survive page reordering.
 */
@Component
@Slf4j
public class ConfluenceEvidenceAdapter {

    private final ConfluenceClient confluence;
    private final ConfluenceStorageToMarkdown storage;
    private final TestBasisExtractor extractor;

    public ConfluenceEvidenceAdapter(ConfluenceClient confluence, ConfluenceStorageToMarkdown storage,
                                     TestBasisExtractor extractor) {
        this.confluence = confluence;
        this.storage = storage;
        this.extractor = extractor;
    }

    public SourceExtraction extract(List<String> pageIds) {
        List<EvidenceUnit> units = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int redactions = 0;
        int fetched = 0;
        for (String pageId : pageIds) {
            try {
                ConfluencePage page = confluence.getPage(pageId);
                NormalizedDoc doc = storage.normalize(page.id(), page.title(), page.storageXhtml());
                // Build the page's units AND redaction subtotal locally, committing both only on success (atomic).
                List<EvidenceUnit> pageUnits = new ArrayList<>();
                int pageRedactions = 0;
                for (TestBasisItem item : extractor.extract(doc)) {
                    // Id hashed from raw source text (stable); hints from the REDACTED text (no scrubbed-token leak).
                    String id = EvidenceId.section(page.id(), anchorOf(item.id(), page.id()), item.text());
                    Redactor.Result r = Redactor.redact(item.text());
                    pageRedactions += r.count();
                    pageUnits.add(EvidenceUnit.of(id, SourceKind.CONFLUENCE, UnitType.DESIGN,
                            page.title(), r.text(), null, Hints.fromText(r.text())));
                }
                // A page that yielded no usable units doesn't count as fetched (§1.3 "fetched zero usable units").
                if (!pageUnits.isEmpty()) {
                    units.addAll(pageUnits);
                    redactions += pageRedactions;
                    fetched++;
                }
            } catch (Exception e) {
                failed.add(pageId + ": " + e.getMessage());
                log.warn("Skipping Confluence page {}: {}", pageId, e.getMessage());
            }
        }
        return new SourceExtraction(SourceKind.CONFLUENCE, units, pageIds.size(), fetched, failed, redactions);
    }

    /** Recover the section slug from a {@code <pageId>#<section>-<n>} basis-item id (drops the trailing counter). */
    static String anchorOf(String itemId, String pageId) {
        if (itemId == null || !itemId.contains("#")) {
            return "section";
        }
        String tail = itemId.substring(itemId.indexOf('#') + 1).replaceAll("-\\d+$", "");
        return tail.isBlank() ? "section" : tail;
    }
}
