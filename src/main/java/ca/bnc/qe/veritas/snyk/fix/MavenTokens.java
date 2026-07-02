package ca.bnc.qe.veritas.snyk.fix;

import java.util.regex.Pattern;

/**
 * Validates the Maven tokens Veritas writes into a {@code pom.xml} — dependency coordinates (groupId/artifactId) and
 * versions. Only {@code [A-Za-z0-9._-]} is allowed, which covers every real Maven token (e.g. {@code 1.0.10},
 * {@code 1.7.15.1}, {@code 2.0.0-SNAPSHOT}, {@code com.fasterxml.jackson.core}) while rejecting the XML
 * metacharacters ({@code < > & " '}) and whitespace that would otherwise let a crafted value inject markup into a
 * pom that is subsequently built with {@code mvn} — an XML-injection → arbitrary-code-execution vector, since the
 * poisoned pom could declare, say, an {@code exec-maven-plugin} execution.
 */
public final class MavenTokens {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]+");

    private MavenTokens() {
    }

    /** A Maven version. Returns it unchanged, or throws {@link IllegalArgumentException} if it isn't a safe token. */
    public static String version(String value) {
        return check(value, "version");
    }

    /** A groupId or artifactId. Returns it unchanged, or throws {@link IllegalArgumentException} if unsafe. */
    public static String coordinate(String value) {
        return check(value, "coordinate");
    }

    /** True when the token is safe to write verbatim into a pom (letters, digits, dot, dash, underscore only). */
    public static boolean isSafe(String value) {
        return value != null && SAFE.matcher(value).matches();
    }

    private static String check(String value, String what) {
        if (!isSafe(value)) {
            throw new IllegalArgumentException(
                    "Unsafe Maven " + what + " (only letters, digits, '.', '-', '_' are allowed): " + value);
        }
        return value;
    }
}
