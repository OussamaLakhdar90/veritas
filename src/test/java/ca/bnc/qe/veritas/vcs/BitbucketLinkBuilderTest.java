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

    @Test
    void serverDcBranchLinkBrowsesTheRefWithNoFilePath() {
        BitbucketLinkBuilder b = builderWith("SERVER_DC", "https://git.bnc.ca/", null);
        assertThat(b.branchLink("APP7488", "lsist-bom", "feature/LSIST-439-snyk-fix-app-7488")).contains(
                "https://git.bnc.ca/projects/APP7488/repos/lsist-bom/browse"
                        + "?at=refs%2Fheads%2Ffeature%2FLSIST-439-snyk-fix-app-7488");
    }

    @Test
    void cloudBranchLinkUsesTheWorkspaceBranchShape() {
        BitbucketLinkBuilder b = builderWith("CLOUD", "https://bitbucket.org", "nbc");
        assertThat(b.branchLink("ignored", "repo", "feature/x")).contains(
                "https://bitbucket.org/nbc/repo/branch/feature%2Fx");
    }

    @Test
    void branchLinkEmptyWhenCoordinatesOrBaseMissing() {
        assertThat(builderWith("SERVER_DC", null, null).branchLink("APP", "repo", "b")).isEmpty();   // no base
        assertThat(builderWith("SERVER_DC", "https://git.bnc.ca", "P").branchLink("APP", "repo", " ")).isEmpty(); // blank branch
        assertThat(builderWith("SERVER_DC", "https://git.bnc.ca", null).branchLink(null, "repo", "b")).isEmpty(); // no project
        assertThat(builderWith("CLOUD", "https://bitbucket.org", null).branchLink("A", "repo", "b")).isEmpty();   // no workspace
    }
}
