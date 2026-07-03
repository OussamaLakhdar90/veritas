package ca.bnc.qe.veritas.vcs;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.HttpFactory;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bitbucket Cloud git host: app-id (project key) → repo discovery, branch listing, shallow clone.
 * REST {@code /2.0}; Basic (app password) or Bearer (OAuth) auth via the per-user {@link SecretProvider}.
 * URI/auth building is package-visible for unit tests; the HTTP/clone calls are validated against git.bnc.
 *
 * <p>Both this and {@link BitbucketServerClient} are always beans; {@link GitHostRouter} (the {@code @Primary}
 * {@link GitHost}) picks between them per request from the live {@code bitbucket.edition} — so a setting change
 * applies without a restart.
 */
@Component
@Slf4j
public class BitbucketCloudClient implements GitHost {

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder().requestFactory(HttpFactory.bounded()).build();
    private final Retries retries;

    public BitbucketCloudClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper, Retries retries) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.retries = retries;
    }

    @Override
    public List<RepoInfo> discoverRepos(String appId) {
        List<RepoInfo> repos = new ArrayList<>();
        String uri = buildDiscoveryUri(appId);
        try {
            while (uri != null) {
                final String pageUri = uri;
                String body = retries.call(() -> http.get().uri(URI.create(pageUri))
                        .header("Authorization", authHeader())
                        .retrieve().body(String.class));
                JsonNode root = mapper.readTree(body == null ? "{}" : body);
                for (JsonNode v : root.path("values")) {
                    repos.add(toRepo(v));
                }
                JsonNode next = root.path("next");
                uri = next.isMissingNode() || next.isNull() ? null : next.asText(null);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket discovery failed for app-id '" + appId + "': " + e.getMessage(), e);
        }
        return repos;
    }

    @Override
    public List<String> listBranches(String appId, String repoSlug) {
        List<String> branches = new ArrayList<>();
        String uri = base() + "/2.0/repositories/" + workspace() + "/" + seg(repoSlug, "repository slug")
                + "/refs/branches?pagelen=100";
        try {
            while (uri != null) {
                final String pageUri = uri;
                String body = retries.call(() -> http.get().uri(URI.create(pageUri))
                        .header("Authorization", authHeader())
                        .retrieve().body(String.class));
                JsonNode root = mapper.readTree(body == null ? "{}" : body);
                for (JsonNode v : root.path("values")) {
                    branches.add(v.path("name").asText());
                }
                JsonNode next = root.path("next");
                uri = next.isMissingNode() || next.isNull() ? null : next.asText(null);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket branch listing failed for '" + repoSlug + "': " + e.getMessage(), e);
        }
        return branches;
    }

    @Override
    // try-with-resources opens Git purely to auto-close; the handle is intentionally unused
    @SuppressWarnings("try")
    public Path clone(RepoInfo repo, String branch, Path destinationParent) {
        Path target = destinationParent.resolve(repo.slug());
        // TODO(insecure-tls): JGit uses its own TLS stack, not CorpHttp. When veritas.http.insecure-tls is on and a
        // corporate proxy intercepts git-over-HTTPS, clone still fails cert validation. Follow-up: honour the flag
        // here too (e.g. http.sslVerify=false via the clone's git config, same server-profile fail-closed guard).
        var cmd = Git.cloneRepository()
                .setURI(repo.cloneUrl())
                .setDirectory(target.toFile())
                .setCredentialsProvider(new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                        secret("GIT_USERNAME"), secret("GIT_TOKEN")))
                .setDepth(1);
        if (branch != null && !branch.isBlank()) {
            cmd.setBranch(branch);
        }
        try (Git git = cmd.call()) {
            log.info("Cloned {} ({}) to {}", repo.slug(), branch, target);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Clone failed for '" + repo.slug() + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String openPullRequest(String repoSlug, String sourceBranch, String targetBranch,
                                  String title, String description) {
        String uri = buildPullRequestUri(repoSlug);
        try {
            String payload = buildPullRequestPayload(sourceBranch, targetBranch, title, description);
            // Non-idempotent write: don't replay on a 5xx/read-timeout (would risk a duplicate PR).
            String body = retries.callWrite(() -> http.post().uri(URI.create(uri))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve().body(String.class));
            JsonNode root = mapper.readTree(body == null ? "{}" : body);
            String html = root.path("links").path("html").path("href").asText("");
            return html.isBlank() ? root.path("id").asText("") : html;
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket PR creation failed for '" + repoSlug + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String openPullRequest(PullRequestSpec spec) {
        // Cloud addresses repos by workspace, not project — spec.project() is not used in the URL. Reviewers are
        // best-effort (Cloud wants account UUIDs); BNC uses Server/DC, where reviewers are honored by username.
        String uri = buildPullRequestUri(spec.repoSlug());
        try {
            String payload = buildPullRequestPayload(spec.sourceBranch(), spec.targetBranch(),
                    spec.title(), spec.description(), spec.reviewers());
            String body = retries.callWrite(() -> http.post().uri(URI.create(uri))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve().body(String.class));
            JsonNode root = mapper.readTree(body == null ? "{}" : body);
            String html = root.path("links").path("html").path("href").asText("");
            return html.isBlank() ? root.path("id").asText("") : html;
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket PR creation failed for '" + spec.repoSlug() + "': " + e.getMessage(), e);
        }
    }

    // ---- testable building blocks ----

    String buildPullRequestUri(String repoSlug) {
        return base() + "/2.0/repositories/" + workspace() + "/" + seg(repoSlug, "repository slug") + "/pullrequests";
    }

    /**
     * Validate a caller-supplied repo slug before it lands raw in a REST path. Bitbucket slugs are
     * {@code [A-Za-z0-9._-]}; anything else (a {@code /}, {@code ..}, {@code ?}, {@code #}) could rewrite the request
     * path under Veritas's credentials — an authenticated SSRF/path-injection. Reject it.
     */
    private static String seg(String value, String what) {
        // Dots are legal in a repo slug (my.repo), so also reject a bare/embedded ".." — a single-segment traversal.
        if (value == null || !value.matches("[A-Za-z0-9._-]+") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid " + what + ": '" + value + "'");
        }
        return value;
    }

    String buildPullRequestPayload(String sourceBranch, String targetBranch, String title, String description)
            throws Exception {
        return buildPullRequestPayload(sourceBranch, targetBranch, title, description, List.of());
    }

    String buildPullRequestPayload(String sourceBranch, String targetBranch, String title, String description,
                                   List<String> reviewers) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("title", title);
        if (description != null && !description.isBlank()) {
            root.put("description", description);
        }
        root.set("source",
                mapper.createObjectNode().set("branch", mapper.createObjectNode().put("name", sourceBranch)));
        if (targetBranch != null && !targetBranch.isBlank()) {
            root.set("destination",
                    mapper.createObjectNode().set("branch", mapper.createObjectNode().put("name", targetBranch)));
        }
        if (reviewers != null && !reviewers.isEmpty()) {
            ArrayNode revs = root.putArray("reviewers");
            for (String r : reviewers) {
                if (r != null && !r.isBlank()) {
                    revs.add(mapper.createObjectNode().put("uuid", r.trim()));
                }
            }
        }
        return mapper.writeValueAsString(root);
    }

    String buildDiscoveryUri(String appId) {
        String q = URLEncoder.encode("project.key=\"" + appId + "\"", StandardCharsets.UTF_8);
        return base() + "/2.0/repositories/" + workspace() + "?q=" + q + "&pagelen=100";
    }

    @Override
    public String whoAmI() {
        try {
            String resp = retries.call(() -> http.get().uri(URI.create(base() + "/2.0/user"))
                    .header("Authorization", authHeader()).retrieve().body(String.class));
            JsonNode n = mapper.readTree(resp == null ? "{}" : resp);
            return n.path("username").asText(n.path("display_name").asText("authenticated"));
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket /2.0/user failed: " + e.getMessage(), e);
        }
    }

    String authHeader() {
        String type = connections.getBitbucket().getAuthType();
        if (type != null && type.equalsIgnoreCase("OAUTH")) {
            return "Bearer " + secret("GIT_TOKEN");
        }
        String basic = secret("GIT_USERNAME") + ":" + secret("GIT_TOKEN");
        return "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
    }

    private RepoInfo toRepo(JsonNode v) {
        String cloneUrl = "";
        for (JsonNode c : v.path("links").path("clone")) {
            if ("https".equalsIgnoreCase(c.path("name").asText())) {
                cloneUrl = c.path("href").asText("");
            }
        }
        return new RepoInfo(
                v.path("slug").asText(v.path("name").asText("")),
                v.path("name").asText(""),
                v.path("description").asText(""),
                v.path("mainbranch").path("name").asText(""),
                cloneUrl,
                v.path("project").path("key").asText(""),
                v.path("updated_on").asText(""));
    }

    private String base() {
        String b = connections.getBitbucket().getBaseUrl();
        if (b == null) {
            return "";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String workspace() {
        return connections.getBitbucket().getWorkspace();
    }

    private String secret(String key) {
        return secrets.get(key).orElse("");
    }
}
