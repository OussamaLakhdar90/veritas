package ca.bnc.qe.veritas.integration.snyk;

/**
 * A Snyk target — a source repository under an org (e.g. the Bitbucket repo {@code application-tests}).
 * This is the "repo to watch" the user selects. {@code id} is the UUID used to filter that org's projects.
 */
public record SnykTarget(String id, String displayName) {
}
