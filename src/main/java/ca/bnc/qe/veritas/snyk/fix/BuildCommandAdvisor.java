package ca.bnc.qe.veritas.snyk.fix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Works out HOW to build &amp; test one consumer app for the Snyk fix reactor, so the app is tested the way its project
 * actually needs instead of a bare {@code mvn test} (which mis-fires when the app's own pom needs a TestNG suite file
 * or a profile — surfacing as a false "breaking change"). A high-end model ({@link ModelTier#DEEP}) reads a SCOPED
 * view of the app — its pom(s), its TestNG/JUnit suite XML files (path + content), and a shallow directory listing —
 * and returns the correct {@code mvn} command. The result is:
 * <ul>
 *   <li><b>security-gated</b> by {@link BuildCommandGuard} (an LLM-produced command is an execution sink), degrading
 *       to the safe default on any guard failure — the reactor never runs an unvetted command;</li>
 *   <li><b>cached per app</b> in {@link AppBuildCommand}, invalidated on a build-config hash change, so it is computed
 *       once and reused;</li>
 *   <li><b>degrade-safe</b> — Copilot offline / a malformed reply / a schema miss all fall back to the default, never
 *       block the fix.</li>
 * </ul>
 * Advisory only, billed to the cost ledger like every other LLM call.
 */
@Service
@Slf4j
public class BuildCommandAdvisor {

    /** The safe fallback: the same bare command the reactor used before the advisor existed. */
    static final String DEFAULT_COMMAND = "mvn -q -B test";

    private static final String OUTPUT_CONTRACT = """
            Emit ONLY a single fenced ```json block, nothing else:
            {"command": "mvn …", "rationale": string}
            The command MUST start with "mvn", run the app's tests (include a test/verify/install phase), and use ONLY:
            the -q/-B/-o verbosity flags, -P<profiles>, -D<key>=<value> (e.g. a suite file pointing INSIDE the repo),
            and the test/verify/install/clean phases. NEVER use plugin:goal targets, shell operators, absolute paths,
            or paths outside the repo. Do NOT add -Dmaven.repo.local (the reactor sets it).
            """;

    private static final int MAX_SCAN_DEPTH = 8;
    private static final int MAX_SUITE_FILES = 25;
    private static final int MAX_SUITE_CHARS = 8000;
    private static final int MAX_LISTED_DIRS = 200;

    private final LlmGateway llm;
    private final PromptComposer composer;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ObjectMapper mapper;
    private final AppBuildCommandRepository cache;

    public BuildCommandAdvisor(LlmGateway llm, PromptComposer composer, ModelSelector modelSelector,
                               CostRecorder costRecorder, JsonBlockExtractor jsonExtractor,
                               ResponseSchemaValidator schemaValidator, ObjectMapper mapper,
                               AppBuildCommandRepository cache) {
        this.llm = llm;
        this.composer = composer;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.mapper = mapper;
        this.cache = cache;
    }

    /**
     * The reactor {@code mvn} command for this app — cached (keyed by appId+repoSlug, invalidated on a build-config
     * change) and always {@link BuildCommandGuard}-safe. Returns the derived command, or the safe default if the
     * advisor is unavailable/fails. Deterministic for a given app config (so the reactor is reproducible).
     */
    public String resolve(String appId, String repoSlug, Path repoDir, String owner, String refId) {
        String pom = readPom(repoDir);
        List<SuiteFile> suites = discoverSuiteFiles(repoDir);
        String hash = configHash(pom, suites);
        Optional<AppBuildCommand> existing = cache.findByAppIdAndRepoSlug(appId, repoSlug);
        if (existing.isPresent() && hash.equals(existing.get().getPomHash())) {
            return safeOrDefault(existing.get().getCommand(), repoDir);   // cache hit — reuse (re-guarded)
        }
        AdvisedCommand advised = deriveCommand(appId, pom, suites, repoDir, owner, refId);
        persist(existing.orElseGet(AppBuildCommand::new), appId, repoSlug, hash, advised);
        return advised.command();
    }

    private AdvisedCommand deriveCommand(String appId, String pom, List<SuiteFile> suites, Path repoDir,
                                         String owner, String refId) {
        if (llm == null || !llm.isAvailable()) {
            log.info("Build-command advisor: Copilot offline — {} will use the default reactor command.", appId);
            return new AdvisedCommand(DEFAULT_COMMAND, "Copilot offline — default reactor command.");
        }
        try {
            String inputs = composer.data("POM", pom == null ? "(no pom.xml found)" : pom)
                    + composer.data("SUITE_FILES", renderSuites(suites))
                    + composer.data("PROJECT_LAYOUT", directoryListing(repoDir));
            String model = modelSelector.resolveTier(ModelTier.DEEP);
            String prompt = composer.compose("[BUILD-COMMAND-ADVISOR]", "build-command-advisor.prompt.md",
                    Set.of(), inputs, OUTPUT_CONTRACT, modelSelector.promptTokenCap(model));
            String raw = llm.complete(prompt, model);
            costRecorder.record("snyk-fix", "build-command-advisor", model, prompt, raw, owner, refId);
            JsonNode node = mapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "build-command-advisor.schema.json");
            String candidate = node.path("command").asText("");
            String rationale = node.path("rationale").asText("");
            String safe = BuildCommandGuard.sanitize(candidate, repoDir);   // throws if not allow-listed → caught below
            log.info("Build-command advisor: {} will be tested with '{}' ({})", appId, safe, rationale);
            return new AdvisedCommand(safe, rationale);
        } catch (Exception e) {
            // A malformed reply, a schema miss, OR a command that fails the security allow-list must NEVER block or
            // subvert the reactor — degrade to the safe default and let the build run normally.
            log.warn("Build-command advisor failed for {} — using the default reactor command: {}", appId, e.getMessage());
            return new AdvisedCommand(DEFAULT_COMMAND, "Advisor unavailable/failed — default reactor command.");
        }
    }

    /** Re-guard a cached command (guard rules may have tightened since it was stored) — default if it no longer passes. */
    private String safeOrDefault(String command, Path repoDir) {
        try {
            return BuildCommandGuard.sanitize(command, repoDir);
        } catch (RuntimeException e) {
            log.warn("Cached build command '{}' no longer passes the guard ({}) — using the default.",
                    command, e.getMessage());
            return DEFAULT_COMMAND;
        }
    }

    private void persist(AppBuildCommand row, String appId, String repoSlug, String hash, AdvisedCommand advised) {
        row.setAppId(appId);
        row.setRepoSlug(repoSlug);
        row.setPomHash(hash);
        row.setCommand(advised.command());
        row.setRationale(advised.rationale());
        try {
            cache.save(row);
        } catch (RuntimeException e) {
            // Caching is best-effort — a save race (two fixes for the same app) must not fail the fix.
            log.debug("Could not cache the build command for {}/{}: {}", appId, repoSlug, e.getMessage());
        }
    }

    private static String readPom(Path repoDir) {
        if (repoDir == null) {
            return null;
        }
        try {
            Path pom = repoDir.resolve("pom.xml");
            return Files.exists(pom) ? Files.readString(pom) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** TestNG/JUnit suite XMLs: {@code testng*.xml}, {@code *suite*.xml}, or an XML under {@code src/test/resources}
     *  whose content is actually a suite (so logback-test.xml et al. don't add noise). Bounded scan. */
    private List<SuiteFile> discoverSuiteFiles(Path repoDir) {
        if (repoDir == null || !Files.isDirectory(repoDir)) {
            return List.of();
        }
        List<SuiteFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoDir, MAX_SCAN_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(BuildCommandAdvisor::isXmlCandidate)
                    .limit(MAX_SUITE_FILES)
                    .forEach(p -> {
                        String content = readCapped(p);
                        if (isSuiteContent(p, content)) {
                            out.add(new SuiteFile(repoDir.relativize(p).toString().replace('\\', '/'), content));
                        }
                    });
        } catch (IOException e) {
            log.debug("Suite-file discovery failed under {}: {}", repoDir, e.getMessage());
        }
        return out;
    }

    private static boolean isXmlCandidate(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xml")) {
            return false;
        }
        if (name.startsWith("testng") || name.contains("suite")) {
            return true;
        }
        return p.toString().replace('\\', '/').toLowerCase(Locale.ROOT).contains("/src/test/resources/");
    }

    private static boolean isSuiteContent(Path p, String content) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith("testng") || name.contains("suite")) {
            return true;   // name is authoritative
        }
        String c = content == null ? "" : content.toLowerCase(Locale.ROOT);
        return c.contains("<suite") || c.contains("testng");   // a resources-dir xml that really is a suite
    }

    private static String directoryListing(Path repoDir) {
        if (repoDir == null || !Files.isDirectory(repoDir)) {
            return "(no project directory)";
        }
        List<String> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoDir, 2)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> !isIgnoredDir(p))
                    .limit(MAX_LISTED_DIRS)
                    .forEach(p -> {
                        String rel = repoDir.relativize(p).toString().replace('\\', '/');
                        if (!rel.isBlank()) {
                            dirs.add(rel);
                        }
                    });
        } catch (IOException e) {
            return "(directory listing failed)";
        }
        Collections.sort(dirs);
        return dirs.isEmpty() ? "(no subdirectories)" : String.join("\n", dirs);
    }

    private static boolean isIgnoredDir(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        return name.equals("target") || name.equals(".git") || name.equals("node_modules") || name.equals(".idea");
    }

    private static String renderSuites(List<SuiteFile> suites) {
        if (suites.isEmpty()) {
            return "(no TestNG/JUnit suite XML files found)";
        }
        StringBuilder sb = new StringBuilder();
        for (SuiteFile s : suites) {
            sb.append("--- ").append(s.path()).append(" ---\n").append(s.content()).append("\n\n");
        }
        return sb.toString();
    }

    private static String readCapped(Path p) {
        try {
            String s = Files.readString(p);
            return s.length() <= MAX_SUITE_CHARS ? s : s.substring(0, MAX_SUITE_CHARS) + "\n…[truncated]…";
        } catch (IOException e) {
            return "";
        }
    }

    /** SHA-256 over the pom + each suite (path + content), sorted — the cache-invalidation fingerprint. */
    private static String configHash(String pom, List<SuiteFile> suites) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((pom == null ? "" : pom).getBytes(StandardCharsets.UTF_8));
            suites.stream().sorted(Comparator.comparing(SuiteFile::path)).forEach(s -> {
                md.update(s.path().getBytes(StandardCharsets.UTF_8));
                md.update((s.content() == null ? "" : s.content()).getBytes(StandardCharsets.UTF_8));
            });
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(pom == null ? 0 : pom.hashCode());   // SHA-256 is always present; defensive
        }
    }

    /** A discovered suite file: its repo-relative path and (capped) content. */
    private record SuiteFile(String path, String content) {}

    /** The advisor's answer: the guard-safe command and a short rationale. */
    private record AdvisedCommand(String command, String rationale) {}
}
