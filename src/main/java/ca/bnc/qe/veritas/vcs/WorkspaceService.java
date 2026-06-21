package ca.bnc.qe.veritas.vcs;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/**
 * Resolves the working copy of a service to validate: either a local path, or — given an app-id + repo
 * slug — discovers the repo via the {@link GitHost} and shallow-clones it to a temp dir.
 */
@Service
public class WorkspaceService {

    private final GitHost gitHost;

    public WorkspaceService(GitHost gitHost) {
        this.gitHost = gitHost;
    }

    public Path resolve(String appId, String repoSlug, String branch, String repoPath) {
        if (repoPath != null && !repoPath.isBlank()) {
            return Path.of(repoPath);
        }
        if (appId != null && !appId.isBlank() && repoSlug != null && !repoSlug.isBlank()) {
            RepoInfo repo = gitHost.discoverRepos(appId).stream()
                    .filter(r -> r.slug().equalsIgnoreCase(repoSlug))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Repo '" + repoSlug + "' not found (or not accessible) under app-id '" + appId + "'"));
            try {
                Path tmp = Files.createTempDirectory("veritas-" + repoSlug + "-");
                return gitHost.clone(repo, branch, tmp);
            } catch (Exception e) {
                throw new IllegalStateException("Clone failed for " + repoSlug + ": " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("Provide a local --repo, or both --app-id and --repo-slug");
    }
}
