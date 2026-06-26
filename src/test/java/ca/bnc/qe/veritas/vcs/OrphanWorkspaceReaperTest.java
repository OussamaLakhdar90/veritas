package ca.bnc.qe.veritas.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The orphan-clone reaper deletes only old veritas-* dirs in the temp root; recent and non-clone dirs are kept. */
class OrphanWorkspaceReaperTest {

    private OrphanWorkspaceReaper reaper(Path tempRoot, long ageMinutes) {
        return new OrphanWorkspaceReaper(ageMinutes, tempRoot.toString());
    }

    private Path mkdir(Path root, String name, Instant lastModified) throws Exception {
        Path d = Files.createDirectory(root.resolve(name));
        Files.writeString(d.resolve("repo.txt"), "x");   // make it non-empty so the recursive delete is exercised
        Files.setLastModifiedTime(d, FileTime.from(lastModified));
        return d;
    }

    @Test
    void reapsOnlyOldCloneDirsAndLeavesRecentAndNonCloneDirs(@TempDir Path tempRoot) throws Exception {
        Instant old = Instant.now().minus(5, ChronoUnit.HOURS);
        Instant recent = Instant.now();
        Path oldClone = mkdir(tempRoot, "veritas-ciam-policies-abc", old);
        Path freshClone = mkdir(tempRoot, "veritas-cards-xyz", recent);
        Path unrelated = mkdir(tempRoot, "some-other-tool-123", old);   // old but NOT a veritas clone

        reaper(tempRoot, 120).reap();   // 2-hour window

        assertThat(Files.exists(oldClone)).as("old clone reaped").isFalse();
        assertThat(Files.exists(freshClone)).as("recent clone kept").isTrue();
        assertThat(Files.exists(unrelated)).as("non-clone dir untouched").isTrue();
    }

    @Test
    void doesNothingWhenTheTempRootIsMissing(@TempDir Path tempRoot) {
        // A non-existent temp root must not throw.
        reaper(tempRoot.resolve("nope"), 120).reap();
    }

    @Test
    void neverDeletesTheTempRootItself(@TempDir Path tempRoot) throws Exception {
        // Even with a zero-minute window, the root dir itself is never a reap target (only veritas-* children).
        Files.writeString(tempRoot.resolve("keep.txt"), "x");
        reaper(tempRoot, 0).reap();
        assertThat(Files.exists(tempRoot)).isTrue();
    }
}
