package ca.bnc.qe.veritas.integration.confluence;

/** A fetched Confluence page (Cloud). {@code storageXhtml} is body.storage, normalized downstream. */
public record ConfluencePage(String id, String title, String storageXhtml) {}
