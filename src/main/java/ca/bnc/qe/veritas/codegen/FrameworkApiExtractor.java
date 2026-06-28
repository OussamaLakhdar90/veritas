package ca.bnc.qe.veritas.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Extracts the REAL public API surface of the BNC <strong>lsist</strong> test-framework libraries
 * ({@code lsist-test-framework-core} / {@code lsist-test-framework-api}) from their SOURCES, so the codegen prompt can
 * be handed a deterministic {@code FRAMEWORK_API} evidence block — the actual {@code WorldKey} enum constants plus the
 * method signatures the generated tests call — instead of the LLM assuming or hallucinating them.
 *
 * <p>This is the evidence-first, $0 (no LLM) alternative to a hand-written framework doc (which goes stale) or asking
 * the model to free-read the library (only works interactively, costs more, less reliable). The block is rendered from
 * the actually-resolved dependency, so it never drifts from the version under test. No-op (empty) when the framework
 * sources aren't available — the template's prose still applies.
 */
@Component
@Slf4j
public class FrameworkApiExtractor {

    /** The framework classes whose public methods the generated tests call (curated — keeps the block small/cheap). */
    private static final Set<String> CLASSES = new LinkedHashSet<>(List.of(
            "RestClient", "RobotToken", "AssertionHelper", "ApiEnvironment", "Validate", "TestData", "JSONUtils"));

    /**
     * Render a compact {@code FRAMEWORK_API} block from the framework sources under {@code frameworkSourceRoot}, or
     * {@link Optional#empty()} when the path is missing or contains none of the expected classes.
     */
    public Optional<String> extract(Path frameworkSourceRoot) {
        if (frameworkSourceRoot == null || !Files.isDirectory(frameworkSourceRoot)) {
            log.info("FrameworkApiExtractor: no framework source dir at [{}] — skipping FRAMEWORK_API block", frameworkSourceRoot);
            return Optional.empty();
        }
        JavaParser parser = new JavaParser();
        List<String> worldKeys = new ArrayList<>();
        Map<String, List<String>> classMethods = new LinkedHashMap<>();
        int[] files = {0};
        try (Stream<Path> walk = Files.walk(frameworkSourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                Optional<CompilationUnit> cu;
                try {
                    cu = parser.parse(p).getResult();
                } catch (Exception e) {
                    log.debug("FrameworkApiExtractor: could not parse {}: {}", p, e.getMessage());
                    return;
                }
                if (cu.isEmpty()) {
                    return;
                }
                files[0]++;
                // WorldKey enum constants (wherever the enum is declared).
                cu.get().findAll(EnumDeclaration.class).stream()
                        .filter(en -> en.getNameAsString().equals("WorldKey"))
                        .forEach(en -> en.getEntries().forEach(c -> {
                            if (!worldKeys.contains(c.getNameAsString())) {
                                worldKeys.add(c.getNameAsString());
                                log.debug("FrameworkApiExtractor: WorldKey += {} ({})", c.getNameAsString(), p.getFileName());
                            }
                        }));
                // Public method signatures of the curated framework classes (+ one level of nested classes, e.g. Validate.Objects).
                cu.get().findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(c -> CLASSES.contains(c.getNameAsString()))
                        .forEach(c -> {
                            List<String> sigs = classMethods.computeIfAbsent(c.getNameAsString(), k -> new ArrayList<>());
                            collectPublicMethods(c, sigs);
                            log.debug("FrameworkApiExtractor: {} → {} public method(s) ({})", c.getNameAsString(), sigs.size(), p.getFileName());
                        });
            });
        } catch (Exception e) {
            log.warn("FrameworkApiExtractor: walk of [{}] failed: {}", frameworkSourceRoot, e.getMessage());
        }
        log.info("FrameworkApiExtractor: parsed {} source files under [{}] — WorldKey={} ; classes={}",
                files[0], frameworkSourceRoot, worldKeys, classMethods.keySet());
        if (worldKeys.isEmpty() && classMethods.isEmpty()) {
            log.warn("FrameworkApiExtractor: found no WorldKey enum or framework classes under [{}] — emitting NO "
                    + "FRAMEWORK_API block (point veritas.codegen.framework-source-dir at the lsist-test-framework sources)",
                    frameworkSourceRoot);
            return Optional.empty();
        }
        return Optional.of(render(worldKeys, classMethods));
    }

    private void collectPublicMethods(ClassOrInterfaceDeclaration c, List<String> out) {
        // Methods are listed under the class's own header (render() prints "ClassName:"), so direct methods need no
        // name prefix; nested classes (e.g. Validate.Objects) get a "Nested." qualifier so the static path is clear.
        for (MethodDeclaration m : c.getMethods()) {
            if (m.isPublic()) {
                out.add(signature(m, ""));
            }
        }
        for (Object member : c.getMembers()) {
            if (member instanceof ClassOrInterfaceDeclaration nested) {
                for (MethodDeclaration m : nested.getMethods()) {
                    if (m.isPublic()) {
                        out.add(signature(m, nested.getNameAsString() + "."));
                    }
                }
            }
        }
    }

    private String signature(MethodDeclaration m, String nameQualifier) {
        List<String> params = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            params.add(p.getType().asString() + (p.isVarArgs() ? "..." : ""));
        }
        return m.getType().asString() + " " + nameQualifier + m.getNameAsString() + "(" + String.join(", ", params) + ")";
    }

    private String render(List<String> worldKeys, Map<String, List<String>> classMethods) {
        StringBuilder sb = new StringBuilder();
        sb.append("The REAL framework API, extracted from the lsist-test-framework sources. This is AUTHORITATIVE — use ")
                .append("ONLY these WorldKey constants and method signatures; never invent or assume any other member.\n");
        if (!worldKeys.isEmpty()) {
            sb.append("\nWorldKey constants: ").append(String.join(", ", worldKeys));
        }
        classMethods.forEach((cls, sigs) -> {
            if (!sigs.isEmpty()) {
                sb.append("\n").append(cls).append(":");
                sigs.stream().distinct().forEach(s -> sb.append("\n  ").append(s));
            }
        });
        return sb.toString();
    }
}
