package ca.bnc.qe.veritas.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Engine-Evolution config ({@code veritas.evolve.*}) — the classification-learning loop that promotes a
 * field-learned severity into {@code DiffEngine.severityOf} via a reviewed PR against Veritas's own repo.
 * <ul>
 *   <li>the evidence <b>bar</b> ({@code minVotes} across {@code minDistinctServices}) guards how much
 *       cross-project agreement a code change needs — one team's opinion never moves the engine;</li>
 *   <li>the <b>target repo</b> ({@code repoAppId} + {@code repoSlug}) is where the self-PR opens. It is left
 *       unset until the deployment repo is known — proposals are still collected + shown, but the "open PR"
 *       step is disabled until it is configured.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "veritas.evolve")
@Getter
@Setter
public class EvolveProperties {

    /** Minimum human classification votes (distinct findings) a type needs before it can be proposed. Default 3. */
    private int minVotes = 3;

    /** Minimum DISTINCT services those votes must span — cross-project convergence, not one team's call. Default 2. */
    private int minDistinctServices = 2;

    /** App-id of Veritas's OWN repo, where a classification PR opens. Blank until the deployment repo is known. */
    private String repoAppId = "";

    /** Repo slug of Veritas's own repo. Blank until known — "open PR" is disabled while unset. */
    private String repoSlug = "";

    /** The base branch the classification PR targets. */
    private String targetBranch = "main";

    /** Enable the daily background poll that recomputes proposals from the field votes. Off by default — a refresh
     *  calls the AI advisor (LLM cost); when off, use the on-demand "Refresh" action. */
    private boolean pollEnabled = false;

    /** Developer-only dry-run: preview the generated DiffEngine.java edit from a LOCAL checkout, written to a review
     *  folder — no clone, gate, or PR. For verifying the classifier produces the right code before a repo is wired up. */
    private final DryRun dryRun = new DryRun();

    /** True once the self-repo target is configured — the gate for enabling the outward "open PR" step. */
    public boolean repoConfigured() {
        return repoAppId != null && !repoAppId.isBlank() && repoSlug != null && !repoSlug.isBlank();
    }

    /**
     * {@code veritas.evolve.dry-run.*} — a debug switch that renders the deterministic {@code DiffEngine.java} edit
     * from a local checkout to a review folder, so the classifier's output can be inspected without a real repo,
     * Bitbucket access, approval gate, or PR. Off by default; the endpoint is refused unless {@code enabled} is true.
     */
    @Getter
    @Setter
    public static class DryRun {

        /** The master switch (the "debug" flag). When false the dry-run endpoint is refused. */
        private boolean enabled = false;

        /** Local checkout of a repo containing {@code DiffEngine.java} — read-only; the edit is never written back here. */
        private String sourceDir = "";

        /** Where to write the edited file + manifest. Blank → {@code <user.home>/.veritas/fixPr}. */
        private String outputDir = "";
    }
}
