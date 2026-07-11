package ca.bnc.qe.veritas.snyk.fix;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Security gate for the AI-derived reactor test command. The command is handed to {@code BuildVerifier}, which runs it
 * with {@code ProcessBuilder} — a command-execution sink — so an LLM-produced (or prompt-injected) command must never
 * become an arbitrary-execution or path-escape vector. This validates every token against a strict allow-list:
 * <ul>
 *   <li>program must be {@code mvn}/{@code mvnw} (Maven only);</li>
 *   <li>each argument must be a known-safe verbosity/behaviour flag, one of the {@code test|verify|install|clean}
 *       phases, a {@code -P<profiles>} activation, or a {@code -D<key>[=<value>]} whose key is not reserved
 *       ({@code maven.repo.local} — the reactor sets that; {@code exec.*} — the exec plugin RCE vector) and whose
 *       value, when it looks like a filesystem path, resolves INSIDE the repo;</li>
 *   <li>at least one test-running phase ({@code test}/{@code verify}/{@code install}) must be present, so a
 *       stripped-down command can't silently run nothing and be mistaken for a pass.</li>
 * </ul>
 * A {@code plugin:goal} token (the {@code exec:exec} RCE vector) has no allow-list entry and is rejected. Anything
 * outside the allow-list throws {@link IllegalArgumentException} naming the offending token — callers fall back to the
 * safe default rather than run an unvetted command. Pure/stateless so it is trivially unit-testable.
 */
public final class BuildCommandGuard {

    private static final Set<String> ALLOWED_PROGRAMS = Set.of("mvn", "mvnw");

    /** Behaviour/verbosity flags with no execution or filesystem implication. */
    private static final Set<String> ALLOWED_FLAGS = Set.of(
            "-q", "--quiet", "-b", "--batch-mode", "-o", "--offline", "-e", "--errors",
            "-ntp", "--no-transfer-progress", "-u", "--update-snapshots",
            "-fae", "--fail-at-end", "-ff", "--fail-fast", "-fn", "--fail-never");

    /** The lifecycle phases the reactor is allowed to drive. {@code clean} is harmless; the rest run tests. */
    private static final Set<String> ALLOWED_PHASES = Set.of("clean", "test", "verify", "install");

    /** Phases that actually execute the app's tests — at least one must be present or the command is a no-op. */
    private static final Set<String> TEST_PHASES = Set.of("test", "verify", "install");

    /**
     * {@code -D} keys we never let the AI set. {@code maven.repo.local} — the reactor owns the local repo;
     * {@code exec.*} — the exec-plugin RCE sink (see {@link #validateSystemProperty}). The test-suppression family
     * defeats the reactor's whole purpose (verify the upgrade by running the app's tests): {@code maven.test.skip}
     * skips test COMPILATION too (hiding API breaks the reactor exists to catch), {@code skipITs} skips the failsafe
     * integration tests (which may BE the app's real tests), and {@code failIfNoTests} can let a zero-tests run pass.
     * Plain {@code -DskipTests} is deliberately NOT here — it still compiles the tests (catching API breaks), so it is
     * a sanctioned compile-only hatch; a passing {@code -DskipTests} run is downgraded to "inconclusive" downstream.
     */
    private static final Set<String> RESERVED_D_KEYS = Set.of(
            "maven.repo.local", "maven.multimoduleprojectdirectory",
            "maven.test.skip", "skipits", "failifnotests");

    private BuildCommandGuard() {
    }

    /**
     * Validate a candidate reactor command against the allow-list. Returns the trimmed command when every token is
     * safe; throws {@link IllegalArgumentException} (naming the offending token) otherwise.
     *
     * @param command the AI-derived command, e.g. {@code mvn -q -B -Psystem-test verify}
     * @param repoDir the app's cloned repo — path-valued {@code -D} args must resolve inside it
     */
    public static String sanitize(String command, Path repoDir) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("empty build command");
        }
        List<String> tokens = List.of(command.trim().split("\\s+"));
        String program = basename(tokens.get(0));
        if (!ALLOWED_PROGRAMS.contains(program)) {
            throw new IllegalArgumentException("program '" + tokens.get(0) + "' is not an allow-listed build tool (mvn)");
        }
        boolean hasTestPhase = false;
        for (String token : tokens.subList(1, tokens.size())) {
            hasTestPhase |= isTestPhase(token);
            validateToken(token, repoDir);
        }
        if (!hasTestPhase) {
            throw new IllegalArgumentException(
                    "command runs no tests (needs one of test/verify/install): '" + command.trim() + "'");
        }
        return command.trim();
    }

    private static boolean isTestPhase(String token) {
        return TEST_PHASES.contains(token.toLowerCase(Locale.ROOT));
    }

    private static void validateToken(String token, Path repoDir) {
        if (containsControlChar(token)) {
            throw new IllegalArgumentException("build-command token contains a control character: '" + token + "'");
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (token.startsWith("-D")) {
            validateSystemProperty(token, repoDir);
            return;
        }
        if (token.startsWith("-P") && token.length() > 2) {
            return;   // profile activation, attached form (-Pfoo,bar)
        }
        if (ALLOWED_FLAGS.contains(lower)) {
            return;
        }
        if (ALLOWED_PHASES.contains(lower)) {
            return;   // a bare lifecycle phase
        }
        // A plugin:goal (e.g. exec:exec), an unknown flag, or a stray path — none are allow-listed.
        throw new IllegalArgumentException("build-command token '" + token + "' is not allow-listed "
                + "(only mvn verbosity flags, -P<profiles>, safe -D<key>[=value], and the test/verify/install/clean phases)");
    }

    private static void validateSystemProperty(String token, Path repoDir) {
        String body = token.substring(2);            // strip -D
        int eq = body.indexOf('=');
        String key = (eq >= 0 ? body.substring(0, eq) : body).toLowerCase(Locale.ROOT);
        String value = eq >= 0 ? body.substring(eq + 1) : "";
        if (key.isBlank()) {
            throw new IllegalArgumentException("malformed -D property: '" + token + "'");
        }
        if (RESERVED_D_KEYS.contains(key) || key.startsWith("exec.")) {
            throw new IllegalArgumentException("-D property '" + key + "' is reserved and may not be set by the advisor");
        }
        if (looksLikePath(value)) {
            requireInsideRepo(value, repoDir, token);
        }
    }

    /** A value is path-like if it carries a separator or a parent ref — those are the only ones that can escape the repo. */
    private static boolean looksLikePath(String value) {
        return value.contains("/") || value.contains("\\") || value.contains("..");
    }

    /** A path-valued property must resolve strictly inside the repo — no absolute paths, no {@code ..} escapes. */
    private static void requireInsideRepo(String value, Path repoDir, String token) {
        if (repoDir == null) {
            throw new IllegalArgumentException("path-valued -D not allowed without a repo context: '" + token + "'");
        }
        Path base = repoDir.toAbsolutePath().normalize();
        Path candidate = Path.of(value);
        Path resolved = (candidate.isAbsolute() ? candidate : base.resolve(candidate)).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("-D path '" + value + "' escapes the repo (" + token + ")");
        }
    }

    private static boolean containsControlChar(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isISOControl(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String basename(String p) {
        return p.replaceAll("^.*[/\\\\]", "").replaceAll("(?i)\\.(cmd|bat|exe|sh|ps1)$", "").toLowerCase(Locale.ROOT);
    }
}
