package ca.bnc.qe.veritas.snyk.fix;

/**
 * Deterministic, authoritative check that a Snyk fix actually raised the vulnerable coordinate to its safe version —
 * the fail-safe that stops a change-less "fix" (only the BOM's own release {@code <version>} moved while the
 * vulnerable dependency was left untouched) from ever being pushed and marked resolved.
 *
 * <p>Scoped to the <b>BOM</b>, which is the one place the cascade pins the coordinate: it resolves the coordinate's
 * effective <em>managed</em> version from the pom text — the {@code <dependencyManagement>} entry's {@code <version>},
 * following a {@code ${property}} reference — <b>without</b> a full Maven resolve. Consumer-side effectiveness (an
 * override a consumer doesn't inherit) needs the resolved dependency tree and is validated separately.
 */
public final class FixValidator {

    private FixValidator() {
    }

    /**
     * The effective managed version of {@code groupId:artifactId} in this pom — the managed {@code <version>} token,
     * resolving a {@code ${property}} reference to its declared value. {@code null} when the pom doesn't manage the
     * coordinate at all (or a {@code ${property}} it uses isn't declared). Reuses {@link PomVersionEditor}'s
     * (management-authoritative for a BOM, where the coordinate appears only under {@code <dependencyManagement>}).
     */
    public static String effectiveVersion(String pom, String groupId, String artifactId) {
        if (pom == null) {
            return null;
        }
        String token = PomVersionEditor.dependencyVersionToken(pom, groupId, artifactId);
        if (token == null) {
            return null;
        }
        if (token.startsWith("${") && token.endsWith("}")) {
            return PomVersionEditor.propertyValue(pom, token.substring(2, token.length() - 1));
        }
        return token;
    }

    /** True when the pom's effective managed version for {@code groupId:artifactId} is exactly {@code fixedIn} — used
     *  as the post-edit assertion (a real upgrade sets the version EXACTLY to fixedIn). */
    public static boolean managesAtVersion(String pom, String groupId, String artifactId, String fixedIn) {
        return fixedIn != null && fixedIn.equals(effectiveVersion(pom, groupId, artifactId));
    }

    /** True when the pom's effective managed version for {@code groupId:artifactId} is at or ABOVE {@code fixedIn} —
     *  i.e. the safe version is already satisfied, so applying {@code fixedIn} would not be an upgrade (a downgrade or
     *  a no-op). Used to decide "already safe → don't touch it" without ever downgrading. */
    public static boolean satisfies(String pom, String groupId, String artifactId, String fixedIn) {
        String effective = effectiveVersion(pom, groupId, artifactId);
        return effective != null && VersionCompare.atOrAbove(effective, fixedIn);
    }
}
