package ca.bnc.qe.veritas.snyk.fix;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.codegen.BuildVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The local reactor gate — the <b>real</b> "does the version bump break?" check that decides whether the PR train
 * opens. Over the already-edited clones it {@code mvn install}s the framework chain (BOM → core → api → web) into a
 * throwaway local repo, then runs <b>each app's own test command</b> (AI-derived per app; see
 * {@link BuildCommandAdvisor}) against it. All pass → clean (open the train); a genuine compile/test failure → the app
 * that broke is named and the fix takes the breaking-change (push-only) path; a build that fails on the app's own
 * <em>config/infrastructure</em> (surefire suite-file config, "no tests", unresolvable plugin) → <b>inconclusive</b>,
 * so an unrelated config error is never reported as a breaking change. Deterministic; uses the allow-listed
 * {@link BuildVerifier} process runner.
 */
@Component
@Slf4j
public class CascadeVerifier {

    /** The fallback when an app has no AI-derived command (advisor off / not yet resolved). */
    static final String DEFAULT_CONSUMER_COMMAND = "mvn -q -B test";

    /**
     * Build-output signatures of a CONFIG/INFRA failure — the app's own build setup broke, not the upgraded code.
     * These make the reactor return "inconclusive" (review manually) instead of a false "breaking change". Kept
     * conservative: a genuine compile error or test-assertion failure does not match these, so a real break still
     * fails. All lower-cased for a case-insensitive contains check.
     */
    private static final List<String> INCONCLUSIVE_SIGNATURES = List.of(
            "suitexmlfile",                       // surefire/TestNG suite-file config, e.g. testSuiteXmlFiles0 has null value
            "has null value",
            "no tests were executed",
            "no tests to run",
            "unknown lifecycle phase",
            "no plugin found",
            "plugin execution not covered",
            "could not resolve dependencies",     // registry/network — not a code break
            "non-resolvable",
            "could not transfer artifact",
            "was cached in the local repository",
            "connection timed out",
            "no goals have been specified");

    /** Test-suppression flags: a build that "passes" with these compiled the app but never RAN its tests, so it is a
     *  compile-only check, not a verified pass. (The guard already blocks maven.test.skip/skipITs; -DskipTests is the
     *  sanctioned compile-only hatch and lands here.) */
    private static final List<String> TEST_SKIP_FLAGS = List.of("-dskiptests", "-dmaven.test.skip");

    private final BuildVerifier buildVerifier;

    /** Per-command timeout for the reactor build — larger than the codegen default (cold repo + multi-module + N test runs). */
    private final long timeoutSeconds;

    public CascadeVerifier(BuildVerifier buildVerifier,
                           @Value("${veritas.snyk.fix.reactor-timeout-seconds:900}") long timeoutSeconds) {
        this.buildVerifier = buildVerifier;
        this.timeoutSeconds = timeoutSeconds;
    }

    public ReactorResult verify(ReactorInputs in) {
        // Nothing to build means nothing was cloned (a total clone/network failure). The two loops below would
        // otherwise fall through to a vacuous pass() and green-light a non-existent fix, so fail the gate instead.
        if (in.framework().isEmpty() && in.consumers().isEmpty()) {
            log.warn("Snyk reactor: no framework modules or consumer apps to build (clone failure?) — failing the gate");
            return ReactorResult.fail("reactor",
                    "Nothing was cloned or built, so the fix could not be verified — check connectivity to the repos.");
        }
        log.info("Snyk reactor: START — installing {} framework module(s) then testing {} consumer app(s) "
                + "(timeout {}s/command).", in.framework().size(), in.consumers().size(), timeoutSeconds);
        String repoArg = "-Dmaven.repo.local=" + in.localRepo().toAbsolutePath();
        for (ModuleBuild m : in.framework()) {
            BuildVerifier.BuildResult r = buildVerifier.verify(m.dir(),
                    "mvn -q -B -DskipTests install " + repoArg, timeoutSeconds);
            if (!"PASS".equals(r.status())) {
                // WARN + the build tail so the console shows the actual mvn failure, not just the module name.
                log.warn("Snyk reactor: framework module {} FAILED to install ({}). Build output tail:\n{}",
                        m.label(), r.status(), tail(r.output()));
                return ReactorResult.fail(m.label(), r.output());
            }
            log.info("Snyk reactor: framework module {} installed OK.", m.label());
        }
        for (ConsumerBuild c : in.consumers()) {
            String command = consumerCommand(c) + " " + repoArg;
            BuildVerifier.BuildResult r = buildVerifier.verify(c.dir(), command, timeoutSeconds);
            if (!"PASS".equals(r.status())) {
                if (isConfigOrInfraFailure(r.output())) {
                    // The app's OWN build config/infra broke (e.g. a TestNG suite-file the pom needs), not the upgrade.
                    // Don't mislabel it "breaking" — return inconclusive so it's held for a human to look at.
                    log.warn("Snyk reactor: consumer {} build is INCONCLUSIVE — config/infra error, not a code break "
                            + "(command '{}'). Build output tail:\n{}", c.appId(), command, tail(r.output()));
                    return ReactorResult.inconclusive("consumer:" + c.appId(), r.output());
                }
                log.warn("Snyk reactor: consumer {} tests FAILED ({}). Build output tail:\n{}",
                        c.appId(), r.status(), tail(r.output()));
                return ReactorResult.fail("consumer:" + c.appId(), r.output());
            }
            if (skipsTestExecution(command)) {
                // Compiled but the tests were SKIPPED — a compile-only check, not a verified pass. Hold for manual
                // review rather than presenting an un-tested upgrade as clean (and never auto-open its PR train).
                log.warn("Snyk reactor: consumer {} compiled but its tests were SKIPPED ('{}') — INCONCLUSIVE "
                        + "(compile-only, not a full test run).", c.appId(), command);
                return ReactorResult.inconclusive("consumer:" + c.appId(),
                        "Tests were skipped (compile-only) for " + c.appId() + " — not a full test run.");
            }
            log.info("Snyk reactor: consumer {} tests passed (command '{}').", c.appId(), command);
        }
        log.info("Snyk reactor: all modules installed and all consumer tests passed — clean.");
        return ReactorResult.pass();
    }

    /** The AI-derived per-app command, or the bare default when the advisor produced none. */
    private static String consumerCommand(ConsumerBuild c) {
        return c.testCommand() == null || c.testCommand().isBlank() ? DEFAULT_CONSUMER_COMMAND : c.testCommand().trim();
    }

    /** True when the build output matches a known config/infra signature (so a failure is inconclusive, not breaking). */
    static boolean isConfigOrInfraFailure(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return INCONCLUSIVE_SIGNATURES.stream().anyMatch(lower::contains);
    }

    /** True when the command suppresses test execution — a passing such build compiled but never ran the tests. */
    static boolean skipsTestExecution(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        return TEST_SKIP_FLAGS.stream().anyMatch(lower::contains);
    }

    /** The last ~2 KB of build output — enough to carry the Maven "BUILD FAILURE" reason into the log without flooding it. */
    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(no build output captured)";
        }
        int max = 2000;
        return output.length() <= max ? output : "…" + output.substring(output.length() - max);
    }

    /** A framework module to install: its label (BOM/core/api/web) and cloned directory. */
    public record ModuleBuild(String label, Path dir) {}

    /** A consumer app to test: its app-id, the cloned application-tests directory, and its AI-derived test command
     *  (blank → the reactor uses {@link #DEFAULT_CONSUMER_COMMAND}). */
    public record ConsumerBuild(String appId, Path dir, String testCommand) {
        /** Convenience for callers/tests that don't supply a command — the reactor falls back to the default. */
        public ConsumerBuild(String appId, Path dir) {
            this(appId, dir, null);
        }
    }

    /** Everything the reactor build needs: a throwaway local repo, the ordered framework chain, and the consumers. */
    public record ReactorInputs(Path localRepo, List<ModuleBuild> framework, List<ConsumerBuild> consumers) {}

    /** The three reactor outcomes: a clean pass, a genuine break, or an inconclusive (config/infra) failure. */
    public enum Outcome { PASS, FAIL, INCONCLUSIVE }

    /**
     * The reactor outcome. A non-PASS names what broke ({@code failingLabel}) + the build tail. {@code INCONCLUSIVE}
     * means the app's own build config/infra failed (not the upgrade), so the fix must be held for manual review
     * rather than reported as a breaking change.
     */
    public record ReactorResult(Outcome outcome, String failingLabel, String outputTail) {
        public static ReactorResult pass() {
            return new ReactorResult(Outcome.PASS, null, null);
        }

        public static ReactorResult fail(String failingLabel, String outputTail) {
            return new ReactorResult(Outcome.FAIL, failingLabel, outputTail);
        }

        public static ReactorResult inconclusive(String failingLabel, String outputTail) {
            return new ReactorResult(Outcome.INCONCLUSIVE, failingLabel, outputTail);
        }

        /** The build was clean — every module installed and every app's tests ran and passed. */
        public boolean passed() {
            return outcome == Outcome.PASS;
        }

        /** The app's own build config/infra failed (not the upgrade) — hold for manual review, don't call it breaking. */
        public boolean inconclusive() {
            return outcome == Outcome.INCONCLUSIVE;
        }
    }
}
