package ca.bnc.qe.veritas.vcs;

import java.nio.file.Path;
import java.util.List;

/** Git host abstraction (Bitbucket Cloud first; Server/DC behind the same interface later). */
public interface GitHost {

    /** Repos under an app-id (Bitbucket Cloud project key within the configured workspace), token-filtered. */
    List<RepoInfo> discoverRepos(String appId);

    /** Branch names for a repo (default branch first). {@code appId} is the project key (Server/DC). */
    List<String> listBranches(String appId, String repoSlug);

    /** Shallow-clone a repo at a branch into {@code destinationParent/<slug>}; returns the clone path. */
    Path clone(RepoInfo repo, String branch, Path destinationParent);

    /**
     * Open a pull request from {@code sourceBranch} into {@code targetBranch}; returns the PR's web URL
     * (or id if the host omits a URL). Outward action — callers gate it behind human approval.
     */
    String openPullRequest(String repoSlug, String sourceBranch, String targetBranch,
                           String title, String description);

    /** Cheap authenticated identity probe (current user) for Test Connection; returns the username or throws. */
    default String whoAmI() {
        throw new UnsupportedOperationException("whoAmI not supported by this git host");
    }
}
