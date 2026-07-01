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

    /** A short human label of what this edit does (for the plan preview + PR body). */
    public String describe() {
        return switch (kind) {
            case PROPERTY_BUMP -> "<" + property + "> " + oldVersion + " → " + newVersion;
            case MANAGED_BUMP -> groupId + ":" + artifactId + " " + oldVersion + " → " + newVersion;
            case ADD_OVERRIDE -> "pin " + groupId + ":" + artifactId + " = " + newVersion;
            case PLUGIN_BUMP -> "plugin " + groupId + ":" + artifactId + " → " + newVersion;
            case VERSION_BUMP -> "release version " + oldVersion + " → " + newVersion;
        };
    }
}
