package ca.bnc.qe.veritas.codegen;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs the template-derived build/verify command on generated tests to prove they compile.
 * Empty command → SKIPPED. Exit 0 → PASS, else FAIL (output captured for the LLM repair pass).
 */
@Component
@Slf4j
public class BuildVerifier {

    /**
     * Allow-list of build/verify executables. The {@code verifyCommand} comes from a (semi-trusted) template's
     * front-matter and is run directly with ProcessBuilder, so it's a command-execution sink — restrict the program
     * to known build tools (no shells: a {@code bash -c …} would re-open arbitrary execution). Args are literal
     * (no shell), so this gates WHAT runs. Refused → SKIPPED (verification not run), never the attacker's program.
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "mvn", "mvnw", "gradle", "gradlew", "npm", "npx", "node", "yarn", "pnpm",
            "make", "dotnet", "python", "python3", "go", "cargo", "ant", "ng", "tsc", "java");

    /** Default per-command timeout (seconds). The Snyk reactor passes a larger one for multi-module install + tests. */
    @Value("${veritas.build.verify-timeout-seconds:300}")
    private long defaultTimeoutSeconds = 300;   // initializer covers direct (non-Spring) construction in tests

    public BuildResult verify(Path workingDir, String command) {
        return verify(workingDir, command, defaultTimeoutSeconds);
    }

    public BuildResult verify(Path workingDir, String command, long timeoutSeconds) {
        if (command == null || command.isBlank()) {
            return new BuildResult("SKIPPED", "");
        }
        if (!isAllowListed(command)) {
            String exe = command.trim().split("\\s+")[0];
            log.warn("Refusing to run verifyCommand '{}' — not an allow-listed build tool; skipping build "
                    + "verification.", exe);
            return new BuildResult("SKIPPED",
                    "verifyCommand '" + exe + "' is not an allow-listed build tool; build verification skipped.");
        }
        try {
            String[] parts = command.trim().split("\\s+");
            parts[0] = resolveProgram(parts[0], workingDir);   // Windows: mvn -> mvn.cmd (+ M2_HOME), or CreateProcess error=2
            Process p = new ProcessBuilder(parts)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = drain(p);
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                // Name the command + budget: a silent hang that ends in a generic "timed out" is undiagnosable otherwise.
                log.warn("Build verify '{}' in {} exceeded the {}s timeout and was killed.",
                        command, workingDir, timeoutSeconds);
                return new BuildResult("FAIL", "build timed out after " + timeoutSeconds + "s");
            }
            return new BuildResult(p.exitValue() == 0 ? "PASS" : "FAIL", tail(out));
        } catch (Exception e) {
            log.warn("Build verify failed to run '{}': {}", command, e.getMessage());
            return new BuildResult("FAIL", e.getMessage());
        }
    }

    /**
     * Is the command's executable an allow-listed build tool? Checked against two candidates because the program
     * can't be tokenised reliably when its path has spaces: (a) the basename of the first whitespace token —
     * covers a bare tool with args ({@code mvn test}); (b) the basename of the command up to the first flag —
     * covers a full executable PATH with spaces ({@code C:\Program Files\…\java.exe --version}).
     */
    static boolean isAllowListed(String command) {
        String c = command.trim();
        String firstToken = c.split("\\s+")[0];
        int flag = c.indexOf(" -");
        String beforeFlags = flag > 0 ? c.substring(0, flag).trim() : c;
        return ALLOWED_TOOLS.contains(basename(firstToken)) || ALLOWED_TOOLS.contains(basename(beforeFlags));
    }

    private static String basename(String p) {
        return p.replaceAll("^.*[/\\\\]", "").replaceAll("(?i)\\.(cmd|bat|exe|sh|ps1)$", "").toLowerCase();
    }

    /**
     * Resolve the build tool to something {@link ProcessBuilder} can actually launch on this OS. On Windows,
     * mvn/gradle/npm/… are {@code .cmd}/{@code .bat} scripts and Java does NOT apply PATHEXT, so a bare {@code "mvn"}
     * dies with "CreateProcess error=2, cannot find the file". We (a) add the right script extension, (b) prefer an
     * absolute path from the tool's home var (M2_HOME/MAVEN_HOME/GRADLE_HOME) so it works even when the tool isn't on
     * the app's PATH — common when Veritas is launched from an IDE — and (c) resolve a project wrapper (mvnw/gradlew)
     * against the working dir. On non-Windows the tool is returned unchanged. Runs only on an already allow-listed
     * tool, so this never widens what can execute.
     */
    static String resolveProgram(String tool, Path workingDir) {
        return resolveProgram(tool, workingDir, System.getProperty("os.name", ""), System.getenv());
    }

    /** Testable core: OS + env are injected so both the Windows and non-Windows branches are exercised on any host. */
    static String resolveProgram(String tool, Path workingDir, String osName, Map<String, String> env) {
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            return tool;
        }
        String script = switch (tool) {
            case "gradle", "gradlew" -> tool + ".bat";
            case "mvn", "mvnw", "npm", "npx", "yarn", "pnpm", "ng", "tsc" -> tool + ".cmd";
            default -> tool;   // node/java/python/go/dotnet/cargo/make are .exe — resolve on PATH as-is
        };
        if (script.equals(tool)) {
            return tool;
        }
        if ("mvnw".equals(tool) || "gradlew".equals(tool)) {
            Path wrapper = workingDir.resolve(script);   // the wrapper lives in the project, not on PATH
            return Files.isRegularFile(wrapper) ? wrapper.toString() : script;
        }
        for (String home : homeVars(tool)) {
            String v = env.get(home);
            if (v != null && !v.isBlank()) {
                Path exe = Path.of(v, "bin", script);
                if (Files.isRegularFile(exe)) {
                    return exe.toString();   // absolute → works regardless of the app's PATH
                }
            }
        }
        return script;   // fall back to the bare .cmd/.bat on PATH
    }

    private static List<String> homeVars(String tool) {
        return switch (tool) {
            case "mvn" -> List.of("M2_HOME", "MAVEN_HOME");
            case "gradle" -> List.of("GRADLE_HOME");
            default -> List.of();
        };
    }

    private String drain(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private String tail(String s) {
        return s.length() <= 4000 ? s : s.substring(s.length() - 4000);
    }

    public record BuildResult(String status, String output) {}
}
