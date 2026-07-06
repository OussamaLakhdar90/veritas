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

    /**
     * Open a pull request with an explicit target project and reviewers — needed by the Snyk fix cascade, which
     * opens PRs in different projects (the framework project vs each app-id) and attaches suggested reviewers.
     * The default delegates to the simpler overload (project + reviewers ignored) for hosts that don't override.
     */
    default String openPullRequest(PullRequestSpec spec) {
        return openPullRequest(spec.repoSlug(), spec.sourceBranch(), spec.targetBranch(),
                spec.title(), spec.description());
    }

    /** Cheap authenticated identity probe (current user) for Test Connection; returns the username or throws. */
    default String whoAmI() {
        throw new UnsupportedOperationException("whoAmI not supported by this git host");
    }

    /**
     * Search host users for the PR-reviewer picker — a username/display-name substring, most-relevant first. Returns
     * real host identities that can actually be added as PR reviewers, so the wizard can autocomplete + validate
     * instead of accepting arbitrary text. Empty if unsupported by this host.
     */
    default List<GitUser> searchUsers(String query, int max) {
        return List.of();
    }

    /** A host user offered in the reviewer picker: {@code name} is the reviewer identity (Server/DC username / Cloud
     *  account id) actually sent when opening the PR; {@code displayName} is for display only. */
    record GitUser(String name, String displayName) {}

    /**
     * A pull request to open. {@code project} targets a specific Bitbucket project (Server/DC) — blank uses the
     * configured default; {@code reviewers} are host usernames (Server/DC) or account UUIDs (Cloud), possibly empty.
     */
    record PullRequestSpec(String project, String repoSlug, String sourceBranch, String targetBranch,
                           String title, String description, List<String> reviewers) {
    }
}
