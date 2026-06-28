package ca.bnc.qe.veritas.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Auto-detects where the BNC <strong>lsist</strong> test-framework sources live for a given destination/test repo, so
 * {@link FrameworkApiExtractor} can read the real {@code WorldKey} + signatures with <em>nothing for the user to
 * configure</em> — the wizard already selects the output repo (the lsist scaffold that depends on the framework), and
 * detection flows from that.
 *
 * <p>Resolution order: (1) a configured override (dev/server escape hatch); (2) sources vendored in the repo tree (the
 * {@code WorldKey} enum is checked in); (3) the framework's {@code -sources.jar} from the local Maven repository,
 * unzipped to a temp dir. Returns {@link Optional#empty()} (a no-op — generation proceeds without the block) when none
 * is found, always with a clear log line explaining why.
 */
@Component
@Slf4j
public class FrameworkSourceLocator {

    /** Artifact/identifier fragment that marks the framework dependency in a pom and its -sources.jar file name. */
    private static final String FRAMEWORK_HINT = "lsist-test-framework";

    /** A located sources directory; {@code temporary} dirs (an unzipped jar) are deleted by the caller after use. */
    public record LocatedSources(Path dir, boolean temporary) {}

    private final String manualOverride;
    private final String m2RepoOverride;

    public FrameworkSourceLocator(
            @Value("${veritas.codegen.framework-source-dir:}") String manualOverride,
            @Value("${veritas.codegen.m2-repo:}") String m2RepoOverride) {
        this.manualOverride = manualOverride;
        this.m2RepoOverride = m2RepoOverride;
    }

    public Optional<LocatedSources> locate(Path destinationRepo) {
        // 1. explicit override — a dev/server escape hatch; not required for the normal auto-detect flow.
        if (manualOverride != null && !manualOverride.isBlank()) {
            Path o = Path.of(manualOverride);
            if (Files.isDirectory(o)) {
                log.info("Framework sources: using configured override [{}]", o);
                return Optional.of(new LocatedSources(o, false));
            }
            log.warn("Framework sources: override [{}] is not a directory — falling through to auto-detect", o);
        }
        if (destinationRepo == null || !Files.isDirectory(destinationRepo)) {
            log.info("Framework sources: no destination repo to auto-detect from [{}]", destinationRepo);
            return Optional.empty();
        }
        // 2. vendored in the test repo (the WorldKey enum is checked in alongside the tests).
        Optional<Path> vendored = findVendored(destinationRepo);
        if (vendored.isPresent()) {
            log.info("Framework sources: auto-detected vendored sources under [{}]", vendored.get());
            return Optional.of(new LocatedSources(vendored.get(), false));
        }
        // 3. the dependency's -sources.jar from the local Maven repo, unzipped to a temp dir.
        Optional<LocatedSources> fromJar = findSourcesJar(destinationRepo);
        if (fromJar.isPresent()) {
            return fromJar;
        }
        log.info("Framework sources: not auto-detected for repo [{}] — no vendored WorldKey and no '{}-*-sources.jar' "
                + "in the local Maven repo. Generation continues without a FRAMEWORK_API block; run "
                + "'mvn dependency:sources' in the test repo (or set veritas.codegen.framework-source-dir) to enable it.",
                destinationRepo, FRAMEWORK_HINT);
        return Optional.empty();
    }

    private Optional<Path> findVendored(Path repo) {
        try (Stream<Path> walk = Files.walk(repo)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(this::declaresWorldKey)
                    .findFirst()
                    .map(Path::getParent);   // the package dir holding WorldKey; the extractor walks from here
        } catch (IOException e) {
            log.debug("Framework sources: vendored scan of [{}] failed: {}", repo, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean declaresWorldKey(Path javaFile) {
        try {
            return Files.readString(javaFile).contains("enum WorldKey");
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<LocatedSources> findSourcesJar(Path repo) {
        if (!repoReferencesFramework(repo)) {
            log.debug("Framework sources: repo [{}] declares no '{}' dependency — skipping Maven-repo lookup", repo, FRAMEWORK_HINT);
            return Optional.empty();
        }
        Path m2 = m2Root();
        if (!Files.isDirectory(m2)) {
            log.info("Framework sources: local Maven repo [{}] not found — cannot resolve the -sources.jar", m2);
            return Optional.empty();
        }
        Optional<Path> jar = newestSourcesJar(m2);
        if (jar.isEmpty()) {
            log.info("Framework sources: repo references '{}' but no matching -sources.jar is in [{}] "
                    + "(run 'mvn dependency:sources')", FRAMEWORK_HINT, m2);
            return Optional.empty();
        }
        try {
            Path tmp = Files.createTempDirectory("veritas-fwsrc-");
            int n = unzipJavaSources(jar.get(), tmp);
            log.info("Framework sources: unzipped {} .java file(s) from [{}] to [{}]", n, jar.get(), tmp);
            return Optional.of(new LocatedSources(tmp, true));
        } catch (IOException e) {
            log.warn("Framework sources: failed to unzip [{}]: {}", jar.get(), e.getMessage());
            return Optional.empty();
        }
    }

    private boolean repoReferencesFramework(Path repo) {
        try (Stream<Path> walk = Files.walk(repo)) {
            return walk.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .anyMatch(p -> {
                        try {
                            return Files.readString(p).contains(FRAMEWORK_HINT);
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    private Path m2Root() {
        if (m2RepoOverride != null && !m2RepoOverride.isBlank()) {
            return Path.of(m2RepoOverride);
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    private Optional<Path> newestSourcesJar(Path m2) {
        try (Stream<Path> walk = Files.walk(m2)) {
            return walk.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(FRAMEWORK_HINT) && n.endsWith("-sources.jar");
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            log.debug("Framework sources: scan of [{}] failed: {}", m2, e.getMessage());
            return Optional.empty();
        }
    }

    private int unzipJavaSources(Path jar, Path dest) throws IOException {
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().endsWith(".java")) {
                    continue;
                }
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) {
                    continue;   // zip-slip guard
                }
                Files.createDirectories(out.getParent());
                Files.copy(zip, out);
                count++;
            }
        }
        return count;
    }
}
