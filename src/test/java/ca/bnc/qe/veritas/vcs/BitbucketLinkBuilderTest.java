package ca.bnc.qe.veritas.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import org.junit.jupiter.api.Test;

/** The edition-aware Bitbucket browse-URL builder: Server/DC vs Cloud shapes, and the "no link" guards. */
class BitbucketLinkBuilderTest {

    private BitbucketLinkBuilder builderWith(String edition, String baseUrl, String workspace) {
        ConnectionsProperties cp = new ConnectionsProperties();
        cp.getBitbucket().setEdition(edition);
        cp.getBitbucket().setBaseUrl(baseUrl);
        cp.getBitbucket().setWorkspace(workspace);
        return new BitbucketLinkBuilder(cp);
    }

    @Test
    void serverDcBuildsProjectRepoBrowseUrlWithRefAndLineAnchor() {
        BitbucketLinkBuilder b = builderWith("SERVER_DC", "https://git.bnc.ca/", null);
        Optional<String> url = b.fileLink("APP7571", "ciam-policies", "develop",
                "src/main/java/ca/bnc/PolicyController.java", 45);
        assertThat(url).contains(
                "https://git.bnc.ca/projects/APP7571/repos/ciam-policies/browse/src/main/java/ca/bnc/PolicyController.java"
                        + "?at=refs%2Fheads%2Fdevelop#45");
    }

    @Test
    void serverDcFallsBackToWorkspaceProjectWhenAppIdMissing() {
        BitbucketLinkBuilder b = builderWith("SERVER_DC", "https://git.bnc.ca", "FALLBACKPROJ");
        Optional<String> url = b.fileLink(null, "repo", "main", "A.java", null);
        assertThat(url).contains("https://git.bnc.ca/projects/FALLBACKPROJ/repos/repo/browse/A.java?at=refs%2Fheads%2Fmain");
        assertThat(url.orElse("")).doesNotContain("#");   // no line → no anchor
    }

    @Test
    void cloudBuildsWorkspaceSrcUrlWithLinesAnchor() {
        BitbucketLinkBuilder b = builderWith("CLOUD", "https://bitbucket.org", "nbc");
        Optional<String> url = b.fileLink("ignored-project", "ciam-policies", "develop", "src/Foo.java", 12);
        assertThat(url).contains("https://bitbucket.org/nbc/ciam-policies/src/develop/src/Foo.java#lines-12");
    }

    @Test
    void emptyWhenBaseUrlOrRepoOrPathMissing() {
        BitbucketLinkBuilder noBase = builderWith("SERVER_DC", null, null);
        assertThat(noBase.fileLink("APP", "repo", "main", "A.java", 1)).isEmpty();

        BitbucketLinkBuilder ok = builderWith("SERVER_DC", "https://git.bnc.ca", "P");
        assertThat(ok.fileLink("APP", "  ", "main", "A.java", 1)).isEmpty();         // blank repo
        assertThat(ok.fileLink("APP", "repo", "main", "?", 1)).isEmpty();            // unresolved path placeholder
        assertThat(ok.fileLink("APP", "repo", "main", null, 1)).isEmpty();           // no path
    }

    @Test
    void cloudEmptyWhenWorkspaceMissing() {
        BitbucketLinkBuilder b = builderWith("CLOUD", "https://bitbucket.org", null);
        assertThat(b.fileLink("APP", "repo", "main", "A.java", 1)).isEmpty();
    }
}
