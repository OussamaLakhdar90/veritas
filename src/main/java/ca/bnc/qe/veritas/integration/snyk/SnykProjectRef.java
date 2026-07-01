package ca.bnc.qe.veritas.integration.snyk;

/**
 * A Snyk project — one scanned manifest under a target (e.g. {@code profile-management/pom.xml} on {@code develop}).
 * {@code id} is the project UUID (same across REST and v1); {@code targetFile} is the pom path, {@code branch} the
 * scanned reference.
 */
public record SnykProjectRef(String id, String name, String targetFile, String branch) {
}
