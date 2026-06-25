package ca.bnc.qe.veritas.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import ca.bnc.qe.veritas.evidence.adapter.CodeEvidenceAdapter;
import ca.bnc.qe.veritas.evidence.adapter.ConfluenceEvidenceAdapter;
import ca.bnc.qe.veritas.evidence.adapter.JiraEvidenceAdapter;
import ca.bnc.qe.veritas.evidence.adapter.SourceExtraction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The §2 extract-once entry point: runs whichever sources a {@link SourceSelection} names through their adapters,
 * combines them into one {@link ExtractionResult}, and computes the realised {@link SourceMix} from fetch
 * <b>successes</b>. Each source runs independently with a backstop try/catch, so a total failure of one source
 * (e.g. Confluence down) degrades to a recorded blind spot rather than aborting the others (design §1.3).
 *
 * <p>Deterministic and $0 — no LLM. Nothing decides feature clustering here; that's the (later) feature index.
 */
@Service
@Slf4j
public class EvidenceExtractor {

    private final CodeEvidenceAdapter code;
    private final JiraEvidenceAdapter jira;
    private final ConfluenceEvidenceAdapter confluence;

    public EvidenceExtractor(CodeEvidenceAdapter code, JiraEvidenceAdapter jira, ConfluenceEvidenceAdapter confluence) {
        this.code = code;
        this.jira = jira;
        this.confluence = confluence;
    }

    public ExtractionResult extract(SourceSelection sel) {
        List<SourceExtraction> parts = new ArrayList<>();
        if (sel.hasCode()) {
            parts.add(safe(() -> code.extract(sel.code()), SourceKind.CODE));
        }
        if (sel.hasJira()) {
            parts.add(safe(() -> jira.extract(sel.jql(), sel.maxResults()), SourceKind.JIRA));
        }
        if (sel.hasConfluence()) {
            parts.add(safe(() -> confluence.extract(sel.pageIds()), SourceKind.CONFLUENCE));
        }

        List<EvidenceUnit> units = new ArrayList<>();
        Map<SourceKind, FetchProvenance.Counts> bySource = new LinkedHashMap<>();
        int redactions = 0;
        for (SourceExtraction p : parts) {
            units.addAll(p.units());
            bySource.put(p.kind(), p.counts());
            redactions += p.redactions();
        }
        FetchProvenance provenance = new FetchProvenance(bySource);
        SourceMix mix = provenance.toMix();
        log.info("Evidence extracted: {} units across {} (mix code={} jira={} confluence={}); {} redactions",
                units.size(), sel.selected(), mix.code(), mix.jira(), mix.confluence(), redactions);
        return new ExtractionResult(units, provenance, mix, redactions, sel.selected());
    }

    /** Backstop: an unexpected adapter failure becomes a recorded empty fetch (requested=1 → §1.3 hard-fail), not a throw. */
    private SourceExtraction safe(Supplier<SourceExtraction> run, SourceKind kind) {
        try {
            return run.get();
        } catch (Exception e) {
            log.warn("{} extraction failed: {}", kind, e.getMessage());
            return new SourceExtraction(kind, List.of(), 1, 0, List.of(kind + " extraction failed: " + e.getMessage()), 0);
        }
    }
}
