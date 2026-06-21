package ca.bnc.qe.veritas.ingest;

/**
 * One atomic, citable element of the test basis. {@code id} (e.g. {@code JIRA-123#ac-2}) lets the LLM cite
 * the source without us resending full text — the foundation of the requirements traceability matrix.
 */
public record TestBasisItem(
        String id,
        String origin,       // source id (issue key / page id)
        TestBasisKind kind,
        String text
) {}
