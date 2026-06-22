package ca.bnc.qe.veritas.vcs;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@code @Primary} {@link GitHost}: routes every call to the Cloud or Server/DC client based on the LIVE
 * {@code veritas.connections.bitbucket.edition}. Because the edition is read per request (not at context
 * startup), changing it in Settings applies immediately — no restart — and one ambiguous bean problem is
 * avoided (both concrete clients are always present, but only this one is injected).
 */
@Component
@Primary
public class GitHostRouter implements GitHost {

    private final BitbucketCloudClient cloud;
    private final BitbucketServerClient server;
    private final ConnectionsProperties connections;

    public GitHostRouter(BitbucketCloudClient cloud, BitbucketServerClient server, ConnectionsProperties connections) {
        this.cloud = cloud;
        this.server = server;
        this.connections = connections;
    }

    private GitHost active() {
        String edition = connections.getBitbucket().getEdition();
        return "SERVER_DC".equalsIgnoreCase(edition) ? server : cloud;
    }

    @Override
    public List<RepoInfo> discoverRepos(String appId) {
        return active().discoverRepos(appId);
    }

    @Override
    public List<String> listBranches(String repoSlug) {
        return active().listBranches(repoSlug);
    }

    @Override
    public Path clone(RepoInfo repo, String branch, Path destinationParent) {
        return active().clone(repo, branch, destinationParent);
    }

    @Override
    public String openPullRequest(String repoSlug, String sourceBranch, String targetBranch, String title, String description) {
        return active().openPullRequest(repoSlug, sourceBranch, targetBranch, title, description);
    }

    @Override
    public String whoAmI() {
        return active().whoAmI();
    }
}
