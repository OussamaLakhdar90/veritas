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

    /** True once the self-repo target is configured — the gate for enabling the outward "open PR" step. */
    public boolean repoConfigured() {
        return repoAppId != null && !repoAppId.isBlank() && repoSlug != null && !repoSlug.isBlank();
    }
}
