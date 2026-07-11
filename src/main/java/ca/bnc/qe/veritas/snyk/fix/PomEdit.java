package ca.bnc.qe.veritas.snyk.fix;

/**
 * One version edit to apply to a pom. Which fields matter depends on {@link #kind()}: PROPERTY_BUMP uses
 * {@code property}; MANAGED_BUMP / ADD_OVERRIDE use {@code groupId}/{@code artifactId}; VERSION_BUMP uses only the
 * versions. {@code oldVersion} is for display ("was → now").
 */
public record PomEdit(FixEditKind kind, String groupId, String artifactId, String property,
                      String oldVersion, String newVersion) {

    public static PomEdit property(String property, String oldVersion, String newVersion) {
        return new PomEdit(FixEditKind.PROPERTY_BUMP, null, null, property, oldVersion, newVersion);
    }

    public static PomEdit managed(String groupId, String artifactId, String oldVersion, String newVersion) {
        return new PomEdit(FixEditKind.MANAGED_BUMP, groupId, artifactId, null, oldVersion, newVersion);
    }

    public static PomEdit override(String groupId, String artifactId, String newVersion) {
        return new PomEdit(FixEditKind.ADD_OVERRIDE, groupId, artifactId, null, null, newVersion);
    }

    public static PomEdit ownVersion(String oldVersion, String newVersion) {
        return new PomEdit(FixEditKind.VERSION_BUMP, null, null, null, oldVersion, newVersion);
    }

    /**
     * A short human label of what this edit does (for the plan preview + PR body). An {@code ADD_OVERRIDE} is called
     * out distinctly from a {@code MANAGED_BUMP} so a reviewer never mistakes an <em>added</em> managed pin (used when
     * the dependency isn't already managed here — verify it takes effect) for a confirmed in-place version bump.
     */
    public String describe() {
        return switch (kind) {
            case PROPERTY_BUMP -> "<" + property + "> " + oldVersion + " → " + newVersion;
            case MANAGED_BUMP -> "Bump " + artifactId + " " + oldVersion + " → " + newVersion;
            case ADD_OVERRIDE -> "Add managed override: " + artifactId + " → " + newVersion
                    + " (not previously managed here — verify it applies)";
            case VERSION_BUMP -> "release version " + oldVersion + " → " + newVersion;
        };
    }
}
