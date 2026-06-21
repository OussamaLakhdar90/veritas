package ca.bnc.qe.veritas.integration.confluence;

/** Confluence read access (Cloud). */
public interface ConfluenceClient {
    ConfluencePage getPage(String pageId);
}
