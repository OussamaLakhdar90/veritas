package ca.bnc.qe.veritas.codegen.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scans an existing test project for the endpoints it appears to exercise — a deterministic, best-effort static read
 * (no LLM, no execution). It walks the test sources, pulls out path-like string literals, and infers the HTTP verb
 * from nearby tokens. The signal is "this path is referenced by a test", which the {@link TestReconciler} matches
 * against the API model. Because string-concatenated URLs and base-path prefixes make exact reconstruction
 * impossible, the matching is heuristic and the resulting plan is always shown for human review.
 */
@Component
public class TestInventoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(TestInventoryExtractor.class);

    /**
     * Test-source file types worth reading — ones where endpoint paths appear as quoted string literals (the
     * Rest-Assured / Karate / Gherkin idiom). {@code .http} files use unquoted request lines and aren't scanned here.
     */
    private static final Set<String> SCAN_EXT = Set.of(".java", ".kt", ".groovy", ".feature");
    /** Directories that never hold hand-written test sources — skipped wholesale. */
    private static final Set<String> SKIP_DIRS = Set.of("target", "build", "out", "bin", ".git", "node_modules", ".idea");

    /** A quoted path literal beginning with '/', e.g. "/policies/{id}" or "/policies/". */
    private static final Pattern PATH_LITERAL = Pattern.compile("[\"'](/[A-Za-z0-9_./{}\\-]*)[\"']");
    /** HTTP verb token (method call, annotation, or .http line) used to tag a nearby path reference. */
    private static final Pattern HTTP_VERB = Pattern.compile("(?i)\\b(get|post|put|patch|delete)\\b");
    /** Looks like a filename rather than an endpoint (…/foo.json, …/schema.yaml) — excluded. */
    private static final Pattern FILE_LIKE = Pattern.compile(".*\\.[A-Za-z0-9]{1,6}$");

    public TestInventory scan(Path testProjectRoot) {
        if (testProjectRoot == null || !Files.isDirectory(testProjectRoot)) {
            return TestInventory.empty();
        }
        List<TestReference> refs = new ArrayList<>();
        int[] files = {0};
        try (Stream<Path> walk = Files.walk(testProjectRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(TestInventoryExtractor::hasScanExt)
                    .filter(p -> !isUnderSkippedDir(testProjectRoot, p))
                    .forEach(p -> {
                        files[0]++;
                        scanFile(testProjectRoot, p, refs);
                    });
        } catch (IOException e) {
            log.warn("test-inventory scan of {} stopped early: {}", testProjectRoot, e.getMessage());
        }
        return new TestInventory(refs, files[0]);
    }

    private void scanFile(Path root, Path file, List<TestReference> refs) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return;   // unreadable/binary — skip silently
        }
        String rel = root.relativize(file).toString().replace('\\', '/');
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = PATH_LITERAL.matcher(lines.get(i));
            while (m.find()) {
                String path = m.group(1);
                if (!looksLikeEndpoint(path)) {
                    continue;
                }
                refs.add(new TestReference(detectVerb(lines, i), path, rel));
            }
        }
    }

    /** A path literal is endpoint-ish when it has a segment beyond '/', isn't a filename, and isn't a package path. */
    private static boolean looksLikeEndpoint(String path) {
        if (path == null || path.length() < 2 || path.contains("..")) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (FILE_LIKE.matcher(last).matches()) {
            return false;   // "/config/app.json", "/schemas/policy.yaml"
        }
        return path.chars().anyMatch(Character::isLetter);   // exclude "/", "/123" style non-routes
    }

    /** First HTTP verb on this line or the two preceding lines — covers fluent calls and annotations above. */
    private static HttpMethod detectVerb(List<String> lines, int idx) {
        for (int j = idx; j >= Math.max(0, idx - 2); j--) {
            Matcher v = HTTP_VERB.matcher(lines.get(j));
            if (v.find()) {
                return HttpMethod.valueOf(v.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return null;
    }

    private static boolean hasScanExt(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return SCAN_EXT.stream().anyMatch(name::endsWith);
    }

    private static boolean isUnderSkippedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path seg : rel) {
            if (SKIP_DIRS.contains(seg.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
