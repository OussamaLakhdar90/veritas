package ca.bnc.qe.veritas.settings;

/** One integration's connection settings (no secrets — tokens live in the secret store). */
public record EndpointView(String baseUrl, String edition, String workspace, String authType) {
}
