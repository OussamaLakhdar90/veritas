package ca.bnc.qe.veritas.snyk.fix;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import ca.bnc.qe.veritas.codegen.BuildVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The local reactor gate — the <b>real</b> "does the version bump break the code?" check that decides whether the PR
 * train opens. Over the already-edited clones it {@code mvn install}s the framework chain (BOM → core → api → web) into
 * a throwaway local repo, then <b>compile-checks each consumer app</b> against it with a fixed
 * {@code mvn clean install -DskipTests}. We deliberately do NOT run the apps' own tests here: an application-tests repo
 * IS the automated test suite, and executing it locally needs suite files, a target environment, and credentials —
 * fragile setup that fails for reasons unrelated to the upgrade. A dependency bump only needs to prove the consumers
 * still <b>compile</b> (test sources are compiled too, so an API break in the framework is caught); the tests
 * themselves run in CI/Jenkins after the PR is opened. All compile → clean (open the train); a genuine compile failure →
 * the app that broke is named and the fix takes the breaking-change (push-only) path; a build that fails on
 * <em>infrastructure</em> (a dependency can't be fetched) → <b>inconclusive</b>, so a transient registry error is never
 * reported as a breaking change. Deterministic; uses the allow-listed {@link BuildVerifier} process runner.
 */
@Component
@Slf4j
public class CascadeVerifier {

    /** The fixed compile-only command every consumer app is built with — compiles main + test sources (catching API
     *  breaks) but never RUNS the tests (those run in CI/Jenkins after the PR). The reactor appends the local-repo arg. */
    static final String CONSUMER_BUILD_COMMAND = "mvn -q -B clean install -DskipTests";

    /**
     * Build-output signatures of an INFRASTRUCTURE failure — the build environment broke, not the upgraded code (e.g. a
     * transitive dependency of the framework can't be fetched). These make the reactor return "inconclusive" (review
     * manually) instead of a false "breaking change". Kept conservative: a genuine compile error does not match these,
     * so a real break still fails the gate. All lower-cased for a case-insensitive contains check.
     */
    private static final List<String> INCONCLUSIVE_SIGNATURES = List.of(
            "could not resolve dependencies",     // registry/network — not a code break
            "non-resolvable",
            "could not transfer artifact",
            "was cached in the local repository",
            "connection timed out");

    private final BuildVerifier buildVerifier;

    /** Per-command timeout for the reactor build — larger than the codegen default (cold repo + multi-module install). */
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
        log.info("Snyk reactor: START — installing {} framework module(s) then compile-checking {} consumer app(s) "
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
            String command = CONSUMER_BUILD_COMMAND + " " + repoArg;
            BuildVerifier.BuildResult r = buildVerifier.verify(c.dir(), command, timeoutSeconds);
            if (!"PASS".equals(r.status())) {
                if (isInfraFailure(r.output())) {
                    // The build ENVIRONMENT broke (a dependency couldn't be fetched), not the upgraded code. Don't
                    // mislabel it "breaking" — return inconclusive so it's held for a human to look at.
                    log.warn("Snyk reactor: consumer {} build is INCONCLUSIVE — infrastructure error, not a code break "
                            + "(command '{}'). Build output tail:\n{}", c.appId(), command, tail(r.output()));
                    return ReactorResult.inconclusive("consumer:" + c.appId(), r.output());
                }
                log.warn("Snyk reactor: consumer {} FAILED to compile against the upgraded framework ({}). "
                        + "Build output tail:\n{}", c.appId(), r.status(), tail(r.output()));
                return ReactorResult.fail("consumer:" + c.appId(), r.output());
            }
            log.info("Snyk reactor: consumer {} compiled OK against the upgraded framework.", c.appId());
        }
        log.info("Snyk reactor: all framework modules installed and all consumers compiled — clean "
                + "(the apps' own tests run in CI/Jenkins after the PR is opened).");
        return ReactorResult.pass();
    }

    /** True when the build output matches a known infrastructure signature (so a failure is inconclusive, not breaking). */
    static boolean isInfraFailure(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return INCONCLUSIVE_SIGNATURES.stream().anyMatch(lower::contains);
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

    /** A consumer app to compile-check: its app-id and the cloned application-tests directory. */
    public record ConsumerBuild(String appId, Path dir) {}

    /** Everything the reactor build needs: a throwaway local repo, the ordered framework chain, and the consumers. */
    public record ReactorInputs(Path localRepo, List<ModuleBuild> framework, List<ConsumerBuild> consumers) {}

    /** The three reactor outcomes: a clean pass, a genuine break, or an inconclusive (infrastructure) failure. */
    public enum Outcome { PASS, FAIL, INCONCLUSIVE }

    /**
     * The reactor outcome. A non-PASS names what broke ({@code failingLabel}) + the build tail. {@code INCONCLUSIVE}
     * means the build infrastructure failed (not the upgrade), so the fix must be held for manual review rather than
     * reported as a breaking change.
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

        /** The build was clean — every framework module installed and every consumer compiled against it. */
        public boolean passed() {
            return outcome == Outcome.PASS;
        }

        /** The build infrastructure failed (not the upgrade) — hold for manual review, don't call it breaking. */
        public boolean inconclusive() {
            return outcome == Outcome.INCONCLUSIVE;
        }
    }
}
