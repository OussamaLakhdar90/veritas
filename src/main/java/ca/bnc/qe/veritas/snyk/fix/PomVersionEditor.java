package ca.bnc.qe.veritas.snyk.fix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Format-preserving edits to a Maven {@code pom.xml} for the Snyk fix cascade. Deliberately string/regex based
 * (not a full Maven model round-trip) so the diff stays minimal and the BNC poms keep their comments/formatting —
 * a clean review is worth more here than a re-serialized tree. Every method returns the edited XML or throws when
 * the target can't be located (the planner turns that into an honest "manual" step).
 */
public final class PomVersionEditor {

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<]*?)\\s*</version>");

    private PomVersionEditor() {
    }

    /** Increment the last numeric dot-segment (1.0.9 → 1.0.10, 1.7.15.1 → 1.7.15.2); appends ".1" if none is numeric. */
    public static String patchBump(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.1";
        }
        String[] parts = version.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches("\\d+")) {
                parts[i] = String.valueOf(Long.parseLong(parts[i]) + 1);
                return String.join(".", parts);
            }
        }
        return version + ".1";
    }

    /** The raw {@code <version>} token of a matched dependency (a literal or a {@code ${prop}}), or null if absent. */
    public static String dependencyVersionToken(String pom, String groupId, String artifactId) {
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            String block = m.group(1);
            if (tag(block, "groupId").equals(groupId) && tag(block, "artifactId").equals(artifactId)) {
                String v = tag(block, "version");
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** The value of a {@code <prop>} property, or null when the property isn't declared. */
    public static String propertyValue(String pom, String prop) {
        Matcher m = property(prop).matcher(pom);
        return m.find() ? m.group(2).trim() : null;
    }

    /** Set an existing {@code <prop>} property's value. Throws if the property isn't present. */
    public static String bumpProperty(String pom, String prop, String newVersion) {
        Matcher m = property(prop).matcher(pom);
        if (!m.find()) {
            throw new IllegalStateException("Property <" + prop + "> not found in pom.");
        }
        return pom.substring(0, m.start()) + m.group(1) + newVersion + m.group(3) + pom.substring(m.end());
    }

    /** Set the literal {@code <version>} inside the matched dependency (only when it's a literal, not a ${property}). */
    public static String bumpDependencyVersion(String pom, String groupId, String artifactId, String newVersion) {
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            String block = m.group(1);
            if (tag(block, "groupId").equals(groupId) && tag(block, "artifactId").equals(artifactId)) {
                Matcher vm = VERSION.matcher(block);
                if (!vm.find()) {
                    throw new IllegalStateException("Dependency " + groupId + ":" + artifactId + " has no <version> to bump.");
                }
                String newBlock = block.substring(0, vm.start()) + "<version>" + newVersion + "</version>"
                        + block.substring(vm.end());
                return pom.substring(0, m.start()) + "<dependency>" + newBlock + "</dependency>" + pom.substring(m.end());
            }
        }
        throw new IllegalStateException("Dependency " + groupId + ":" + artifactId + " not found in pom.");
    }

    /** The module's own {@code <project><version>} (the first {@code <version>} after {@code </parent>}), or null. */
    public static String projectVersion(String pom) {
        Matcher m = projectVersionMatcher(pom);
        return m == null ? null : m.group(1).trim();
    }

    /** Bump the module's own version (release a new artifact version). Throws when it can't be located. */
    public static String bumpProjectVersion(String pom, String newVersion) {
        Matcher m = projectVersionMatcher(pom);
        if (m == null) {
            throw new IllegalStateException("Could not locate the module's own <version> to bump.");
        }
        return pom.substring(0, m.start()) + "<version>" + newVersion + "</version>" + pom.substring(m.end());
    }

    /**
     * Add a forced managed version for an unmanaged (transitive) dependency: a {@code <dependency>} with an explicit
     * {@code <version>} inserted into {@code <dependencyManagement><dependencies>} (the block is created if absent).
     * This is the self-contained way to pin a transitive fix in the BOM.
     */
    public static String addManagedDependency(String pom, String groupId, String artifactId, String newVersion) {
        String entry = "\n            <dependency>\n"
                + "                <groupId>" + groupId + "</groupId>\n"
                + "                <artifactId>" + artifactId + "</artifactId>\n"
                + "                <version>" + newVersion + "</version>\n"
                + "            </dependency>";
        int dm = pom.indexOf("<dependencyManagement>");
        if (dm >= 0) {
            int deps = pom.indexOf("<dependencies>", dm);
            if (deps >= 0) {
                int insert = deps + "<dependencies>".length();
                return pom.substring(0, insert) + entry + pom.substring(insert);
            }
        }
        // No dependencyManagement block — create one just before </project>.
        int end = pom.lastIndexOf("</project>");
        if (end < 0) {
            throw new IllegalStateException("Malformed pom: no </project>.");
        }
        String block = "    <dependencyManagement>\n        <dependencies>" + entry
                + "\n        </dependencies>\n    </dependencyManagement>\n";
        return pom.substring(0, end) + block + pom.substring(end);
    }

    /** The first {@code <version>} after {@code </parent>} (or after {@code </modelVersion>} when there's no parent). */
    private static Matcher projectVersionMatcher(String pom) {
        int from = pom.indexOf("</parent>");
        if (from < 0) {
            from = pom.indexOf("</modelVersion>");
        }
        if (from < 0) {
            from = 0;
        }
        Matcher m = VERSION.matcher(pom);
        if (m.find(from) && !m.group(1).contains("${")) {
            return m;
        }
        return null;
    }

    private static Pattern property(String prop) {
        return Pattern.compile("(<" + Pattern.quote(prop) + ">)([^<]*)(</" + Pattern.quote(prop) + ">)");
    }

    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }
}
