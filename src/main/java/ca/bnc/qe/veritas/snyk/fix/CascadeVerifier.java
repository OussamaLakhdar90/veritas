package ca.bnc.qe.veritas.snyk.fix;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.codegen.BuildVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The local reactor gate — the <b>real</b> "does the version bump break?" check that decides whether the PR train
 * opens. Over the already-edited clones it {@code mvn install}s the framework chain (BOM → core → api → web) into a
 * throwaway local repo, then {@code mvn test}s each selected app against it. All pass → clean (open the train);
 * any fail → the app that broke is named and the fix takes the breaking-change (push-only) path. Deterministic;
 * uses the allow-listed {@link BuildVerifier} process runner.
 */
@Component
@Slf4j
public class CascadeVerifier {

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
            BuildVerifier.BuildResult r = buildVerifier.verify(c.dir(), "mvn -q -B test " + repoArg, timeoutSeconds);
            if (!"PASS".equals(r.status())) {
                log.warn("Snyk reactor: consumer {} tests FAILED ({}). Build output tail:\n{}",
                        c.appId(), r.status(), tail(r.output()));
                return ReactorResult.fail("consumer:" + c.appId(), r.output());
            }
            log.info("Snyk reactor: consumer {} tests passed.", c.appId());
        }
        log.info("Snyk reactor: all modules installed and all consumer tests passed — clean.");
        return ReactorResult.pass();
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

    /** A consumer app to test: its app-id and the cloned application-tests directory. */
    public record ConsumerBuild(String appId, Path dir) {}

    /** Everything the reactor build needs: a throwaway local repo, the ordered framework chain, and the consumers. */
    public record ReactorInputs(Path localRepo, List<ModuleBuild> framework, List<ConsumerBuild> consumers) {}

    /** The reactor outcome. {@code passed=false} names what broke ({@code failingLabel}) + the build tail. */
    public record ReactorResult(boolean passed, String failingLabel, String outputTail) {
        public static ReactorResult pass() {
            return new ReactorResult(true, null, null);
        }

        public static ReactorResult fail(String failingLabel, String outputTail) {
            return new ReactorResult(false, failingLabel, outputTail);
        }
    }
}
