package ca.bnc.qe.veritas.contract;

/** Where a "current YAML" spec comes from at scan time. */
public enum SpecSourceKind {
    /** A path inside the cloned repo (relative to the repo root, or absolute). */
    REPO_PATH,
    /** A live OpenAPI endpoint, e.g. {@code https://host/v3/api-docs} (JSON or YAML). */
    LIVE_DOCS,
    /** A Confluence page id whose body holds the spec in a code block. */
    CONFLUENCE
}
