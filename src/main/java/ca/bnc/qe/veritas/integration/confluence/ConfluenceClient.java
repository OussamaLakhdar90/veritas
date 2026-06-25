package ca.bnc.qe.veritas.integration.confluence;

/** Confluence read access (Cloud). */
public interface ConfluenceClient {
    ConfluencePage getPage(String pageId);

    /**
     * List the pages of a space (id + title, no body) so a user can pick pages without knowing their ids —
     * the space-level discovery the CLI's explicit {@code --confluence <pageIds>} couldn't offer.
     */
    default java.util.List<ConfluencePage> getPagesBySpace(String spaceKey) {
        throw new UnsupportedOperationException("getPagesBySpace not supported by this Confluence client");
    }

    /**
     * The page tree rooted at {@code rootPageRef} (the root itself + every descendant), ids only — so a reviewer can
     * point at one parent page instead of listing every child id. Bounded by {@code maxPages} and a cycle guard.
     */
    default java.util.List<ConfluencePage> descendants(String rootPageRef, int maxPages) {
        throw new UnsupportedOperationException("descendants not supported by this Confluence client");
    }

    /** Cheap authenticated identity probe (current user) for Test Connection; returns the name or throws. */
    default String whoAmI() {
        throw new UnsupportedOperationException("whoAmI not supported by this Confluence client");
    }
}
