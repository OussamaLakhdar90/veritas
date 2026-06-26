package ca.bnc.qe.veritas.vcs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reaps orphaned clone workspaces. {@link WorkspaceService} tracks the temp dirs it clones in an in-heap set and
 * cleans them up in a {@code finally}, but that set is lost on a crash (JVM kill, power loss) — so a
 * {@code veritas-<repo>-<rand>} clone dir is then leaked on disk forever. This sweeps the temp dir for such dirs
 * older than a safety window (so a clone from a concurrently-running scan is never touched) on startup and
 * periodically. Defence in depth: only directories named {@code veritas-*} <b>directly under the temp dir</b> are
 * ever considered, and deletion is hard-bounded to that temp dir.
 */
@Component
@Slf4j
public class OrphanWorkspaceReaper {

    private static final String CLONE_PREFIX = "veritas-";

    private final Path tempRoot;
    private final Duration orphanAge;

    public OrphanWorkspaceReaper(@Value("${veritas.workspace.orphan-age-minutes:120}") long orphanAgeMinutes,
                                 @Value("${veritas.workspace.temp-dir:#{systemProperties['java.io.tmpdir']}}") String tempDir) {
        this.orphanAge = Duration.ofMinutes(orphanAgeMinutes);
        this.tempRoot = Path.of(tempDir).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reapOnStartup() {
        reap();
    }

    @Scheduled(initialDelayString = "${veritas.workspace.reap-ms:3600000}",
            fixedDelayString = "${veritas.workspace.reap-ms:3600000}")
    public void reapScheduled() {
        reap();
    }

    /** Delete {@code veritas-*} clone dirs in the temp root older than the safety window. Best-effort; never throws. */
    void reap() {
        if (!Files.isDirectory(tempRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(orphanAge);
        List<Path> orphans;
        try (Stream<Path> entries = Files.list(tempRoot)) {
            orphans = entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(CLONE_PREFIX))
                    .filter(p -> olderThan(p, cutoff))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not scan {} for orphaned workspaces: {}", tempRoot, e.toString());
            return;
        }
        int reaped = 0;
        for (Path o : orphans) {
            if (deleteRecursively(o)) {
                reaped++;
            }
        }
        if (reaped > 0) {
            log.info("Reaped {} orphaned clone workspace(s) older than {} min from {}",
                    reaped, orphanAge.toMinutes(), tempRoot);
        }
    }

    private boolean olderThan(Path p, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;   // can't tell its age → leave it alone
        }
    }

    private boolean deleteRecursively(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        if (!abs.startsWith(tempRoot) || abs.equals(tempRoot)) {
            log.warn("Refusing to reap a path outside the temp root: {}", root);
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Could not delete {} while reaping: {}", p, e.toString());
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("Failed to reap workspace {}: {}", root, e.toString());
            return false;
        }
    }
}
