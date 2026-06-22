package ca.bnc.qe.veritas.contract;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import ca.bnc.qe.veritas.integration.confluence.ConfluenceClient;
import ca.bnc.qe.veritas.integration.confluence.ConfluencePage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves a {@link SpecSource} into a {@link SpecInput} (the "current YAML") at scan time. Three sources:
 * a path inside the cloned repo, a live OpenAPI endpoint, or a Confluence page. Deterministic (no LLM);
 * the spec content is handed unchanged to the OpenAPI extractor (which accepts JSON or YAML).
 */
@Component
@Slf4j
public class SpecResolver {

    private final ConfluenceClient confluence;
    private final RestClient http = RestClient.builder().build();

    public SpecResolver(ConfluenceClient confluence) {
        this.confluence = confluence;
    }

    public SpecInput resolve(SpecSource source, Path repoPath) {
        return switch (source.kind()) {
            case REPO_PATH -> fromRepo(source.ref(), repoPath);
            case LIVE_DOCS -> fromLiveDocs(source.ref());
            case CONFLUENCE -> fromConfluence(source.ref());
        };
    }

    private SpecInput fromRepo(String ref, Path repoPath) {
        Path p = Path.of(ref);
        Path resolved = p.isAbsolute() ? p : repoPath.resolve(ref);
        if (Files.isRegularFile(resolved)) {
            return read(resolved);
        }
        // The given path doesn't exist — auto-discover the OpenAPI/Swagger spec in the clone.
        List<Path> candidates = findSpecCandidates(repoPath);
        if (candidates.size() == 1) {
            log.info("Spec '{}' not found; using the discovered spec {}", ref, repoPath.relativize(candidates.get(0)));
            return read(candidates.get(0));
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No OpenAPI/Swagger spec found in the repo (looked for '" + ref
                    + "' and scanned for openapi/swagger *.yaml|*.yml|*.json). If the contract lives in Confluence "
                    + "or a live /v3/api-docs endpoint, choose that spec source instead.");
        }
        String list = candidates.stream().map(c -> repoPath.relativize(c).toString().replace('\\', '/'))
                .limit(15).collect(Collectors.joining(", "));
        throw new IllegalStateException("Spec '" + ref + "' not found. Candidate spec files in this repo: "
                + list + " — set the Spec path to one of these.");
    }

    private SpecInput read(Path f) {
        try {
            return new SpecInput(stem(f), Files.readString(f));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read spec file " + f + ": " + e.getMessage(), e);
        }
    }

    /** Find likely OpenAPI/Swagger files in the clone — by name, or a yaml/json whose head declares openapi/swagger. */
    private List<Path> findSpecCandidates(Path root) {
        try (Stream<Path> stream = Files.walk(root, 6)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String rel = root.relativize(f).toString().replace('\\', '/');
                        return !rel.startsWith(".git/") && !rel.contains("/node_modules/")
                                && !rel.contains("/target/") && !rel.contains("/build/");
                    })
                    .filter(this::looksLikeSpec)
                    .limit(50)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean looksLikeSpec(Path f) {
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json"))) {
            return false;
        }
        if (name.contains("openapi") || name.contains("swagger") || name.startsWith("api.")) {
            return true;
        }
        try {   // sniff the head for an OpenAPI/Swagger declaration
            String head = Files.readString(f);
            head = head.substring(0, Math.min(head.length(), 800)).toLowerCase(Locale.ROOT);
            return head.contains("openapi:") || head.contains("\"openapi\"")
                    || head.contains("swagger:") || head.contains("\"swagger\"");
        } catch (Exception e) {
            return false;
        }
    }

    private SpecInput fromLiveDocs(String url) {
        try {
            String body = http.get().uri(URI.create(url)).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("Live API docs at " + url + " returned an empty body");
            }
            log.info("Fetched live API docs from {}", url);
            return new SpecInput("live-api-docs", body);
        } catch (RestClientException e) {
            throw new IllegalStateException("Failed to fetch live API docs from " + url + ": " + e.getMessage(), e);
        }
    }

    private SpecInput fromConfluence(String pageId) {
        ConfluencePage page = confluence.getPage(pageId);
        String spec = ConfluenceSpecExtractor.extractSpec(page.storageXhtml());
        log.info("Extracted spec from Confluence page {} ({})", pageId, page.title());
        return new SpecInput("confluence-" + pageId, spec);
    }

    private String stem(Path p) {
        String f = p.getFileName().toString();
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }
}
