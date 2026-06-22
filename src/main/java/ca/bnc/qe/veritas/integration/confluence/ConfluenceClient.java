package ca.bnc.qe.veritas.integration.confluence;

/** Confluence read access (Cloud). */
public interface ConfluenceClient {
    ConfluencePage getPage(String pageId);

    /** Cheap authenticated identity probe (current user) for Test Connection; returns the name or throws. */
    default String whoAmI() {
        throw new UnsupportedOperationException("whoAmI not supported by this Confluence client");
    }
}
