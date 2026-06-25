package ca.bnc.qe.veritas.vcs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the working copy of a service to validate: either a local path, or — given an app-id + repo
 * slug — discovers the repo via the {@link GitHost} and shallow-clones it to a temp dir.
 *
 * <p>A cloned temp dir is the service's to own: the caller must {@link #cleanup} the returned path when done so
 * the clone doesn't leak on disk (a long-running server would otherwise fill its temp filesystem over time).
 * {@code cleanup} deletes <b>only</b> a directory this service cloned — a user's local {@code repoPath} is never
 * tracked and so is never touched.
 */
@Service
@Slf4j
public class WorkspaceService {

    private final GitHost gitHost;
    /** Temp roots this service created for clones — the allow-list {@link #cleanup} may delete. */
    private final Set<Path> managedClones = ConcurrentHashMap.newKeySet();

    public WorkspaceService(GitHost gitHost) {
        this.gitHost = gitHost;
    }

    public Path resolve(String appId, String repoSlug, String branch, String repoPath) {
        if (repoPath != null && !repoPath.isBlank()) {
            return Path.of(repoPath);   // a user's local path — never tracked, never cleaned up
        }
        if (appId != null && !appId.isBlank() && repoSlug != null && !repoSlug.isBlank()) {
            RepoInfo repo = gitHost.discoverRepos(appId).stream()
                    .filter(r -> r.slug().equalsIgnoreCase(repoSlug))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Repo '" + repoSlug + "' not found (or not accessible) under app-id '" + appId + "'"));
            Path tmp;
            try {
                tmp = Files.createTempDirectory("veritas-" + repoSlug + "-").toAbsolutePath().normalize();
            } catch (IOException e) {
                throw new IllegalStateException("Could not create a workspace for " + repoSlug + ": " + e.getMessage(), e);
            }
            managedClones.add(tmp);
            try {
                return gitHost.clone(repo, branch, tmp);
            } catch (Exception e) {
                // Clone failed: the caller never gets the path, so clean the temp dir here rather than leak it.
                managedClones.remove(tmp);
                deleteRecursively(tmp);
                throw new IllegalStateException("Clone failed for " + repoSlug + ": " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("Provide a local --repo, or both --app-id and --repo-slug");
    }

    /**
     * Delete a working copy IFF this service cloned it to a temp dir — a user's local {@code repoPath} (never in
     * {@link #managedClones}) is left untouched, as is any path this service didn't create. Best-effort and never
     * throws, so it is safe in a {@code finally}.
     */
    public void cleanup(Path workingCopy) {
        if (workingCopy == null) {
            return;
        }
        Path abs = workingCopy.toAbsolutePath().normalize();
        Path root = managedClones.stream().filter(abs::startsWith).findFirst().orElse(null);
        if (root == null) {
            return;   // not a clone we created (e.g. a local repoPath) → never delete it
        }
        managedClones.remove(root);
        deleteRecursively(root);
    }

    private void deleteRecursively(Path root) {
        // Defence in depth: only ever a temp dir we created, but refuse anything outside the system temp dir.
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!root.toAbsolutePath().normalize().startsWith(tmpDir)) {
            log.warn("Refusing to delete workspace outside the temp dir: {}", root);
            return;
        }
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Could not delete {} during workspace cleanup: {}", p, e.toString());
                }
            });
            log.info("Cleaned up cloned workspace {}", root);
        } catch (IOException e) {
            log.warn("Failed to clean up cloned workspace {}: {}", root, e.toString());
        }
    }
}
