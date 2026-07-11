package ca.bnc.qe.veritas.vcs;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import org.springframework.stereotype.Component;

/**
 * Builds a human-browsable Bitbucket URL that deep-links to a file (and line) in the validated repository, so a
 * finding's code evidence is one click away from the dashboard. Edition-aware (Server/Data Center vs Cloud), and
 * returns {@link Optional#empty()} whenever the repo coordinates or the configured base URL aren't enough to form
 * a real link (the UI then just shows the path, no link). Pure string building — no network, read-only config.
 *
 * <p>URL shapes:
 * <ul>
 *   <li><b>Server/DC</b> — {@code {base}/projects/{PROJECT}/repos/{repo}/browse/{path}?at=refs/heads/{branch}#{line}}
 *       (the app-id entered on the Validate screen is the project key).</li>
 *   <li><b>Cloud</b> — {@code {base}/{workspace}/{repo}/src/{branch}/{path}#lines-{line}}.</li>
 * </ul>
 */
@Component
public class BitbucketLinkBuilder {

    private final ConnectionsProperties connections;

    public BitbucketLinkBuilder(ConnectionsProperties connections) {
        this.connections = connections;
    }

    /**
     * @param appId    Server/DC project key (Cloud: ignored — repos live under the configured workspace)
     * @param repoSlug repository slug
     * @param gitRef   branch the scan ran against (Cloud falls back to {@code HEAD} if blank)
     * @param relPath  repo-relative, forward-slashed file path (the extractor now emits these)
     * @param line     1-based line to anchor on (nullable)
     */
    public Optional<String> fileLink(String appId, String repoSlug, String gitRef, String relPath, Integer line) {
        String base = base();
        if (base.isEmpty() || isBlank(repoSlug) || isBlank(relPath) || "?".equals(relPath)) {
            return Optional.empty();
        }
        String path = encodePath(relPath);

        if ("CLOUD".equalsIgnoreCase(edition())) {
            String ws = workspace();
            if (isBlank(ws)) {
                return Optional.empty();
            }
            String ref = isBlank(gitRef) ? "HEAD" : enc(gitRef);
            String url = base + "/" + ws + "/" + repoSlug + "/src/" + ref + "/" + path;
            return Optional.of(line != null ? url + "#lines-" + line : url);
        }

        // Server / Data Center: the app-id is the project key (fall back to the configured workspace/project).
        String project = !isBlank(appId) ? appId : workspace();
        if (isBlank(project)) {
            return Optional.empty();
        }
        String url = base + "/projects/" + project + "/repos/" + repoSlug + "/browse/" + path;
        if (!isBlank(gitRef)) {
            url += "?at=" + enc("refs/heads/" + gitRef);
        }
        return Optional.of(line != null ? url + "#" + line : url);
    }

    /**
     * A browsable link to a whole BRANCH (no file), so a pushed-but-PR-less Snyk fix branch is one click away.
     * Server/DC: {@code {base}/projects/{PROJECT}/repos/{repo}/browse?at=refs/heads/{branch}};
     * Cloud: {@code {base}/{workspace}/{repo}/branch/{branch}}. {@link Optional#empty()} when the coordinates or
     * base URL aren't enough to form a real link.
     */
    public Optional<String> branchLink(String appId, String repoSlug, String branch) {
        String base = base();
        if (base.isEmpty() || isBlank(repoSlug) || isBlank(branch)) {
            return Optional.empty();
        }
        if ("CLOUD".equalsIgnoreCase(edition())) {
            String ws = workspace();
            return isBlank(ws) ? Optional.empty()
                    : Optional.of(base + "/" + ws + "/" + repoSlug + "/branch/" + enc(branch));
        }
        String project = !isBlank(appId) ? appId : workspace();
        if (isBlank(project)) {
            return Optional.empty();
        }
        return Optional.of(base + "/projects/" + project + "/repos/" + repoSlug
                + "/browse?at=" + enc("refs/heads/" + branch));
    }

    private String base() {
        String b = connections.getBitbucket().getBaseUrl();
        if (b == null) {
            return "";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String edition() {
        return connections.getBitbucket().getEdition();
    }

    private String workspace() {
        return connections.getBitbucket().getWorkspace();
    }

    /** Encode each path segment but keep the slashes (Bitbucket browse paths are slash-delimited). */
    private static String encodePath(String relPath) {
        String[] segments = relPath.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(enc(segments[i]));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
