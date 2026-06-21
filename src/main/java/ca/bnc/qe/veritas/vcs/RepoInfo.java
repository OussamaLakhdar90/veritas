package ca.bnc.qe.veritas.vcs;

/** A repository the user can access, as returned by the git host discovery. */
public record RepoInfo(
        String slug,
        String name,
        String description,
        String defaultBranch,
        String cloneUrl,
        String projectKey,
        String updatedOn
) {}
