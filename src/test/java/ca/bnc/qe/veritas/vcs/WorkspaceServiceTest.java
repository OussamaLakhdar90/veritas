package ca.bnc.qe.veritas.vcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The clone temp dirs WorkspaceService creates are cleaned up — but a user's local {@code repoPath} is NEVER
 * deleted (the safety property: cleanup only ever touches a directory this service itself cloned).
 */
class WorkspaceServiceTest {

    private final GitHost gitHost = mock(GitHost.class);
    private final WorkspaceService workspace = new WorkspaceService(gitHost);

    @Test
    void cleanupNeverDeletesAUsersLocalRepoPath(@TempDir Path tempDir) throws Exception {
        Path localRepo = Files.createDirectory(tempDir.resolve("my-local-repo"));
        Files.writeString(localRepo.resolve("pom.xml"), "x");

        Path resolved = workspace.resolve(null, null, null, localRepo.toString());
        assertThat(resolved).isEqualTo(localRepo);   // a local path is returned verbatim, not cloned

        workspace.cleanup(resolved);   // must be a no-op — the service didn't create this directory

        assertThat(localRepo).exists();
        assertThat(localRepo.resolve("pom.xml")).exists();
    }

    @Test
    void cleanupDeletesAClonedWorkspace() throws Exception {
        RepoInfo repo = new RepoInfo("myrepo", "My Repo", null, "main", "https://git/myrepo.git", "APP1", null);
        when(gitHost.discoverRepos("APP1")).thenReturn(List.of(repo));
        when(gitHost.clone(eq(repo), any(), any())).thenAnswer(inv -> {
            Path parent = inv.getArgument(2);                       // the temp root the service created
            Path checkout = Files.createDirectory(parent.resolve("myrepo"));
            Files.writeString(checkout.resolve("cloned.txt"), "x");
            return checkout;                                        // clone lands in tmp/<slug>, like the real clients
        });

        Path clone = workspace.resolve("APP1", "myrepo", "main", null);
        assertThat(clone.resolve("cloned.txt")).exists();

        workspace.cleanup(clone);

        assertThat(clone).doesNotExist();
        assertThat(clone.getParent()).doesNotExist();   // the whole temp root is removed, not just the checkout
    }

    @Test
    void aFailedCloneCleansUpItsTempDirAndDoesNotLeak() {
        RepoInfo repo = new RepoInfo("myrepo", "My Repo", null, "main", "https://git/myrepo.git", "APP1", null);
        when(gitHost.discoverRepos("APP1")).thenReturn(List.of(repo));
        java.util.concurrent.atomic.AtomicReference<Path> tmp = new java.util.concurrent.atomic.AtomicReference<>();
        when(gitHost.clone(eq(repo), any(), any())).thenAnswer(inv -> {
            tmp.set(inv.getArgument(2));                 // the temp dir the service created, before clone fails
            throw new RuntimeException("clone boom");
        });

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> workspace.resolve("APP1", "myrepo", "main", null));

        assertThat(tmp.get()).isNotNull();
        assertThat(tmp.get()).doesNotExist();   // a failed clone doesn't leak its temp dir
    }
}
