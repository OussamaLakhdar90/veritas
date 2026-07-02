package ca.bnc.qe.veritas.vcs;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.HttpFactory;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bitbucket <b>Server/Data Center</b> git host (REST {@code /rest/api/1.0}). Selected by {@link GitHostRouter}
 * when {@code bitbucket.edition=SERVER_DC} (live, no restart). The app-id is the Server/DC <b>project key</b>
 * (entered on the Validate screen for discovery); branch/PR paths fall back to the configured {@code workspace}
 * as the project key. Bearer PAT (default, an HTTP access token) or Basic auth via the {@link SecretProvider}.
 * Page traversal uses Server/DC's {@code isLastPage}/{@code nextPageStart} envelope.
 */
@Component
@Slf4j
public class BitbucketServerClient implements GitHost {

    private final ConnectionsProperties connections;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.builder().requestFactory(HttpFactory.bounded()).build();
    private final Retries retries;

    public BitbucketServerClient(ConnectionsProperties connections, SecretProvider secrets, ObjectMapper mapper, Retries retries) {
        this.connections = connections;
        this.secrets = secrets;
        this.mapper = mapper;
        this.retries = retries;
    }

    @Override
    public List<RepoInfo> discoverRepos(String appId) {
        List<RepoInfo> repos = new ArrayList<>();
        try {
            int start = 0;
            boolean last = false;
            while (!last) {
                final String pageUri = buildDiscoveryUri(appId, start);
                String body = retries.call(() -> http.get().uri(URI.create(pageUri))
                        .header("Authorization", authHeader()).retrieve().body(String.class));
                JsonNode root = mapper.readTree(body == null ? "{}" : body);
                for (JsonNode v : root.path("values")) {
                    repos.add(toRepo(v, appId));
                }
                last = root.path("isLastPage").asBoolean(true);
                start = root.path("nextPageStart").asInt(start);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket Server discovery failed for project '" + appId + "': " + e.getMessage(), e);
        }
        return repos;
    }

    @Override
    public List<String> listBranches(String appId, String repoSlug) {
        // app-id is the Server/DC project key; validate both — they land raw in the REST path (SSRF/path-injection sink).
        String project = seg((appId != null && !appId.isBlank()) ? appId : project(), "project key");
        String repo = seg(repoSlug, "repository slug");
        List<String> branches = new ArrayList<>();
        String defaultBranch = null;
        try {
            int start = 0;
            boolean last = false;
            while (!last) {
                final String pageUri = base() + "/rest/api/1.0/projects/" + project + "/repos/" + repo
                        + "/branches?limit=100&start=" + start;
                String body = retries.call(() -> http.get().uri(URI.create(pageUri))
                        .header("Authorization", authHeader()).retrieve().body(String.class));
                JsonNode root = mapper.readTree(body == null ? "{}" : body);
                for (JsonNode v : root.path("values")) {
                    String name = v.path("displayId").asText(v.path("id").asText(""));
                    branches.add(name);
                    if (v.path("isDefault").asBoolean(false)) {
                        defaultBranch = name;
                    }
                }
                last = root.path("isLastPage").asBoolean(true);
                start = root.path("nextPageStart").asInt(start);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket Server branch listing failed for '" + repoSlug + "': " + e.getMessage(), e);
        }
        // Surface the default branch first so the Validate form pre-selects the right one (e.g. master, not main).
        if (defaultBranch != null && branches.remove(defaultBranch)) {
            branches.add(0, defaultBranch);
        }
        return branches;
    }

    @Override
    // try-with-resources opens Git purely to auto-close; the handle is intentionally unused
    @SuppressWarnings("try")
    public Path clone(RepoInfo repo, String branch, Path destinationParent) {
        Path target = destinationParent.resolve(repo.slug());
        CloneCommand cmd = Git.cloneRepository()
                .setURI(repo.cloneUrl())
                .setDirectory(target.toFile())
                .setDepth(1);
        if (branch != null && !branch.isBlank()) {
            cmd.setBranch(branch);
        }
        // Server/DC git-over-HTTPS: an HTTP access token authenticates as a Bearer header (BEARER), which also
        // works without a username; BASIC uses username + password. Empty-username Basic is what Bitbucket
        // Server rejects as "not authorized".
        String type = connections.getBitbucket().getAuthType();
        if (type != null && type.equalsIgnoreCase("BASIC")) {
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(secret("GIT_USERNAME"), secret("GIT_TOKEN")));
        } else {
            String bearer = "Bearer " + secret("GIT_TOKEN");
            cmd.setTransportConfigCallback(transport -> {
                if (transport instanceof TransportHttp http) {
                    http.setAdditionalHeaders(Map.of("Authorization", bearer));
                }
            });
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
        try {
            String payload = buildPullRequestPayload(sourceBranch, targetBranch, title, description);
            // Non-idempotent write: don't replay on a 5xx/read-timeout (would risk a duplicate PR).
            String body = retries.callWrite(() -> http.post().uri(URI.create(buildPullRequestUri(repoSlug)))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve().body(String.class));
            JsonNode root = mapper.readTree(body == null ? "{}" : body);
            JsonNode self = root.path("links").path("self");
            if (self.isArray() && !self.isEmpty()) {
                return self.get(0).path("href").asText(root.path("id").asText(""));
            }
            return root.path("id").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket Server PR creation failed for '" + repoSlug + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String openPullRequest(PullRequestSpec spec) {
        String project = seg((spec.project() != null && !spec.project().isBlank()) ? spec.project() : project(),
                "project key");
        String repo = seg(spec.repoSlug(), "repository slug");
        try {
            String payload = buildPullRequestPayload(spec.sourceBranch(), spec.targetBranch(),
                    spec.title(), spec.description(), spec.reviewers());
            String uri = base() + "/rest/api/1.0/projects/" + project + "/repos/" + repo + "/pull-requests";
            String body = retries.callWrite(() -> http.post().uri(URI.create(uri))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve().body(String.class));
            JsonNode root = mapper.readTree(body == null ? "{}" : body);
            JsonNode self = root.path("links").path("self");
            if (self.isArray() && !self.isEmpty()) {
                return self.get(0).path("href").asText(root.path("id").asText(""));
            }
            return root.path("id").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket Server PR creation failed for '" + spec.repoSlug()
                    + "' in project '" + project + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String whoAmI() {
        try {
            // Any authenticated Server/DC request echoes the user in the X-AUSERNAME response header.
            var entity = retries.call(() -> http.get().uri(URI.create(base() + "/rest/api/1.0/repos?limit=1"))
                    .header("Authorization", authHeader()).retrieve().toEntity(String.class));
            String user = entity.getHeaders().getFirst("X-AUSERNAME");
            if (user == null || user.isBlank()) {
                throw new IllegalStateException("no X-AUSERNAME header (not authenticated)");
            }
            return user;
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket Server identity probe failed: " + e.getMessage(), e);
        }
    }

    // ---- testable building blocks ----

    String buildDiscoveryUri(String appId, int start) {
        return base() + "/rest/api/1.0/projects/" + seg(appId, "project key") + "/repos?limit=100&start=" + start;
    }

    String buildPullRequestUri(String repoSlug) {
        return base() + "/rest/api/1.0/projects/" + project() + "/repos/" + seg(repoSlug, "repository slug")
                + "/pull-requests";
    }

    /**
     * Validate a caller-supplied path segment (project key / repo slug) before it is concatenated into a REST URL.
     * Bitbucket keys/slugs are {@code [A-Za-z0-9._-]}; anything else (a {@code /}, {@code ..}, {@code ?}, {@code #},
     * or an encoded traversal) could rewrite the request path under Veritas's PAT — an authenticated SSRF. Reject it.
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
        return buildPullRequestPayload(sourceBranch, targetBranch, title, description, java.util.List.of());
    }

    String buildPullRequestPayload(String sourceBranch, String targetBranch, String title, String description,
                                   java.util.List<String> reviewers) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("title", title);
        if (description != null && !description.isBlank()) {
            root.put("description", description);
        }
        root.set("fromRef", mapper.createObjectNode().put("id", "refs/heads/" + sourceBranch));
        if (targetBranch != null && !targetBranch.isBlank()) {
            root.set("toRef", mapper.createObjectNode().put("id", "refs/heads/" + targetBranch));
        }
        if (reviewers != null && !reviewers.isEmpty()) {
            ArrayNode revs = root.putArray("reviewers");
            for (String r : reviewers) {
                if (r != null && !r.isBlank()) {
                    revs.add(mapper.createObjectNode().set("user", mapper.createObjectNode().put("name", r.trim())));
                }
            }
        }
        return mapper.writeValueAsString(root);
    }

    String authHeader() {
        String type = connections.getBitbucket().getAuthType();
        if (type != null && type.equalsIgnoreCase("BASIC")) {
            String basic = secret("GIT_USERNAME") + ":" + secret("GIT_TOKEN");
            return "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + secret("GIT_TOKEN");   // Server/DC PAT (default)
    }

    private RepoInfo toRepo(JsonNode v, String projectKey) {
        String cloneUrl = "";
        for (JsonNode c : v.path("links").path("clone")) {
            if ("http".equalsIgnoreCase(c.path("name").asText()) || "https".equalsIgnoreCase(c.path("name").asText())) {
                cloneUrl = c.path("href").asText("");
            }
        }
        return new RepoInfo(
                v.path("slug").asText(v.path("name").asText("")),
                v.path("name").asText(""),
                v.path("description").asText(""),
                v.path("defaultBranch").asText(""),
                cloneUrl,
                v.path("project").path("key").asText(projectKey),
                v.path("updatedDate").asText(""));
    }

    private String base() {
        String b = connections.getBitbucket().getBaseUrl();
        if (b == null) {
            return "";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String project() {
        return connections.getBitbucket().getWorkspace();   // on Server/DC the "workspace" config holds the project key
    }

    private String secret(String key) {
        return secrets.get(key).orElse("");
    }
}
