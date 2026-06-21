package ca.bnc.qe.veritas.contract;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
        try {
            return new SpecInput(stem(resolved), Files.readString(resolved));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read spec file " + resolved + ": " + e.getMessage(), e);
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
