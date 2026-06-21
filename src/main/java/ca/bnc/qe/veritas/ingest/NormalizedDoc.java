package ca.bnc.qe.veritas.ingest;

/** A source (Jira issue / Confluence page) normalized to lean markdown — the LLM never sees raw ADF/XHTML. */
public record NormalizedDoc(
        String sourceType,   // "jira" | "confluence"
        String sourceId,     // issue key | page id
        String title,
        String markdown
) {}
