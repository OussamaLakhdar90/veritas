package ca.bnc.qe.veritas.snyk.fix;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Compiled {@code <tag>…</tag>} patterns, cached by tag name — {@link #tag} runs in a per-dependency loop. */
    private static final Map<String, Pattern> TAG_PATTERNS = new ConcurrentHashMap<>();

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

    /**
     * The value of a {@code <prop>} property, or null when it isn't declared. Only the active {@code <properties>}
     * block is considered (not a profile/pluginManagement copy), and matches inside XML comments are skipped — so a
     * commented-out or inactive declaration never masquerades as the effective value.
     */
    public static String propertyValue(String pom, String prop) {
        Matcher m = activePropertyMatcher(pom, prop);
        return m == null ? null : m.group(2).trim();
    }

    /** Set the active {@code <properties>} block's {@code <prop>} value. Throws if the property isn't present there. */
    public static String bumpProperty(String pom, String prop, String newVersion) {
        MavenTokens.version(newVersion);   // never write an unsafe value into a pom that will be built
        Matcher m = activePropertyMatcher(pom, prop);
        if (m == null) {
            throw new IllegalStateException("Property <" + prop + "> not found in <properties>.");
        }
        return pom.substring(0, m.start()) + m.group(1) + newVersion + m.group(3) + pom.substring(m.end());
    }

    /** The first non-commented match of {@code <prop>} within the active {@code <properties>} block, or null. */
    private static Matcher activePropertyMatcher(String pom, String prop) {
        int[] region = propertiesRegion(pom);
        Matcher m = property(prop).matcher(pom);
        m.region(region[0], region[1]);
        while (m.find()) {
            if (!insideComment(pom, m.start())) {
                return m;
            }
        }
        return null;
    }

    /** The [start,end) of the first {@code <properties>…</properties>} block, or the whole pom when there's none. */
    private static int[] propertiesRegion(String pom) {
        int start = pom.indexOf("<properties>");
        if (start < 0) {
            return new int[] {0, pom.length()};
        }
        int end = pom.indexOf("</properties>", start);
        return new int[] {start, end < 0 ? pom.length() : end};
    }

    /** True when {@code idx} falls inside an XML comment ({@code <!-- … -->}). */
    private static boolean insideComment(String pom, int idx) {
        int open = pom.lastIndexOf("<!--", idx);
        if (open < 0) {
            return false;
        }
        int close = pom.indexOf("-->", open);
        return close < 0 || close > idx;
    }

    /** Set the literal {@code <version>} inside the matched dependency (only when it's a literal, not a ${property}). */
    public static String bumpDependencyVersion(String pom, String groupId, String artifactId, String newVersion) {
        MavenTokens.version(newVersion);
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
        MavenTokens.version(newVersion);
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
        MavenTokens.coordinate(groupId);
        MavenTokens.coordinate(artifactId);
        MavenTokens.version(newVersion);
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

    /** The module's own {@code <version>} — the first after {@code </parent>}/{@code </modelVersion>} that still sits
     * in the project header, i.e. before any {@code <dependencies>}/{@code <dependencyManagement>}/{@code <build>}/
     * {@code <profiles>}/{@code <modules>} section. A {@code <version>} found only inside one of those sections is a
     * dependency/plugin version, not the module's own, so a module that inherits its version from {@code <parent>}
     * (no own {@code <version>}) yields {@code null} here rather than mangling a real dependency version. */
    private static Matcher projectVersionMatcher(String pom) {
        int from = pom.indexOf("</parent>");
        if (from < 0) {
            from = pom.indexOf("</modelVersion>");
        }
        if (from < 0) {
            from = 0;
        }
        int headerEnd = firstIndexOf(pom, from,
                "<dependencies>", "<dependencyManagement>", "<build>", "<profiles>", "<modules>");
        Matcher m = VERSION.matcher(pom);
        if (m.find(from) && m.start() < headerEnd && !m.group(1).contains("${")) {
            return m;
        }
        return null;
    }

    /** The earliest index (at or after {@code from}) of any of the markers, or {@code pom.length()} when none appear. */
    private static int firstIndexOf(String pom, int from, String... markers) {
        int best = pom.length();
        for (String marker : markers) {
            int i = pom.indexOf(marker, from);
            if (i >= 0 && i < best) {
                best = i;
            }
        }
        return best;
    }

    private static Pattern property(String prop) {
        return Pattern.compile("(<" + Pattern.quote(prop) + ">)([^<]*)(</" + Pattern.quote(prop) + ">)");
    }

    private static String tag(String block, String name) {
        Matcher m = TAG_PATTERNS.computeIfAbsent(name, n -> Pattern.compile("<" + n + ">\\s*([^<]*?)\\s*</" + n + ">"))
                .matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }
}
