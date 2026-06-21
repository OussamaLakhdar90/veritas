package ca.bnc.qe.veritas.engine.model;

import java.util.List;
import java.util.Map;

/**
 * The canonical, language-agnostic intermediate representation produced identically from the Spring code
 * and from each OpenAPI spec. The diff engine compares two {@code ApiModel}s — apples to apples.
 */
public record ApiModel(
        String source,            // "code" | spec id (e.g. "repo-spec", "confluence-spec")
        String title,
        String version,
        String openApiVersion,    // "2.0" | "3.0.x" | "3.1.x" | null for code
        List<Endpoint> endpoints,
        Map<String, SchemaModel> schemas,
        List<String> blindSpots   // static-analysis gaps surfaced to the user (never silently dropped)
) {
    /** Back-compat: most producers have no blind spots. */
    public ApiModel(String source, String title, String version, String openApiVersion,
                    List<Endpoint> endpoints, Map<String, SchemaModel> schemas) {
        this(source, title, version, openApiVersion, endpoints, schemas, List.of());
    }
}
