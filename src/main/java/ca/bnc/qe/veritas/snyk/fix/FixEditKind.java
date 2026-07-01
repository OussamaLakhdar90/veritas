package ca.bnc.qe.veritas.snyk.fix;

/** How a single pom edit in the cascade is applied. */
public enum FixEditKind {
    /** Bump an existing {@code <x.version>} property to the new value. */
    PROPERTY_BUMP,
    /** Bump a literal {@code <version>} inside a matched dependencyManagement entry. */
    MANAGED_BUMP,
    /** Add a new override (property + dependencyManagement entry) for an unmanaged transitive dependency. */
    ADD_OVERRIDE,
    /** Bump a plugin's version. */
    PLUGIN_BUMP,
    /** Bump the module's own {@code <project><version>} (release a new artifact version). */
    VERSION_BUMP
}
