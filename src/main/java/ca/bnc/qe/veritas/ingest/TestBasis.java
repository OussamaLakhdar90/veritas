package ca.bnc.qe.veritas.ingest;

import java.util.List;

/** The compact, deduplicated, ID'd test basis fed to the LLM (instead of raw Jira/Confluence payloads). */
public record TestBasis(List<TestBasisItem> items) {

    public int size() {
        return items == null ? 0 : items.size();
    }
}
