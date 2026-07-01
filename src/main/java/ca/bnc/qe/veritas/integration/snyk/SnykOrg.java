package ca.bnc.qe.veritas.integration.snyk;

/**
 * A Snyk organization — one per BNC app-id. {@code slug} is the human key shown in the UI (e.g. {@code app7576});
 * {@code id} is the UUID the REST/v1 API addresses. Maps to what the user calls "the application id".
 */
public record SnykOrg(String id, String slug, String name) {
}
