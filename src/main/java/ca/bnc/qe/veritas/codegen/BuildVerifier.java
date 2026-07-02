package ca.bnc.qe.veritas.codegen;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private static final java.util.Set<String> ALLOWED_TOOLS = java.util.Set.of(
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
            Process p = new ProcessBuilder(command.trim().split("\\s+"))
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = drain(p);
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new BuildResult("FAIL", "build timed out");
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
