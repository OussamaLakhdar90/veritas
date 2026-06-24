package ca.bnc.qe.veritas.evidence;

import java.util.Set;

/**
 * One normalised, citable element of the test basis — the spine of the multi-source test-strategy pipeline
 * (design §2). Produced deterministically from Jira / Confluence / code / policy by the evidence adapters; the
 * richer successor to {@code ingest.TestBasisItem}.
 *
 * <p>The {@code id} must be <b>stable across source edits</b> (every downstream guarantee — cache, citations,
 * overrides, the PLANNED→IMPLEMENTED status flip, why-doc links — depends on it). Mint ids via {@link EvidenceId}
 * so they are content-derived, not positional. The {@code text} must already be redacted ({@link Redactor}).
 *
 * @param id        stable, content-derived, citable (e.g. {@code JIRA-1012}, {@code CONF-A#auth-flow-3f7k2mzq},
 *                  {@code CODE:AuthController#POST /login}, {@code POLICY:owasp-api4-rate-limiting})
 * @param source    which source kind this came from
 * @param type      what element it is
 * @param title     one-line label for the UI + citation
 * @param text      normalised, <b>redacted</b> content (markdown, already trimmed)
 * @param link      deep link back to the Jira issue / Confluence page#anchor / repo path#class (nullable)
 * @param lifecycle Jira workflow state (TO_DO|IN_PROGRESS|DONE|DESCOPED…); null for non-Jira
 * @param priority  Jira priority; null otherwise
 * @param links     related-unit ids (Jira "blocks"/"relates") — for traceability + the status engine
 * @param hints     cheap deterministic clustering signals (labels, components, endpoint path-nouns)
 */
public record EvidenceUnit(
        String id,
        SourceKind source,
        UnitType type,
        String title,
        String text,
        String link,
        String lifecycle,
        String priority,
        Set<String> links,
        Set<String> hints) {

    public EvidenceUnit {
        links = links == null ? Set.of() : Set.copyOf(links);
        hints = hints == null ? Set.of() : Set.copyOf(hints);
    }

    /** Convenience factory for the common case (no Jira lifecycle/priority/links). */
    public static EvidenceUnit of(String id, SourceKind source, UnitType type, String title, String text,
                                  String link, Set<String> hints) {
        return new EvidenceUnit(id, source, type, title, text, link, null, null, Set.of(), hints);
    }

    /**
     * Factory for the Jira adapter — the richest producer. Hardcodes {@code source=JIRA} and keeps the
     * lifecycle/priority/links fields adjacent and named, so callers don't risk a positional swap on the
     * 10-arg canonical constructor (lifecycle/priority and title/text/link are same-typed and easy to transpose).
     */
    public static EvidenceUnit jira(String id, UnitType type, String title, String text, String link,
                                    String lifecycle, String priority, Set<String> links, Set<String> hints) {
        return new EvidenceUnit(id, SourceKind.JIRA, type, title, text, link, lifecycle, priority, links, hints);
    }
}
