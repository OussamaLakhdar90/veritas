package ca.bnc.qe.veritas.snyk.fix;

/**
 * A small, dependency-free comparator for Maven-style versions — enough to decide whether a coordinate is BELOW, AT,
 * or ABOVE a target so the fix engine never <b>downgrades</b> a dependency in the name of a security fix. Compares the
 * dotted numeric segments left-to-right; a missing segment is treated as 0 ({@code 2.18} == {@code 2.18.0}); a
 * trailing {@code -qualifier} (SNAPSHOT/RC/RELEASE) is ignored for the numeric ordering. Non-numeric segments compare
 * lexically as a last resort. Intentionally simple: it decides direction (up vs down), not a full semver precedence.
 */
public final class VersionCompare {

    private VersionCompare() {
    }

    /** {@code <0} when {@code a} is older than {@code b}, {@code 0} when equal, {@code >0} when {@code a} is newer. */
    public static int compare(String a, String b) {
        String[] as = core(a);
        String[] bs = core(b);
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String sa = i < as.length ? as[i] : "0";
            String sb = i < bs.length ? bs[i] : "0";
            Long na = asLong(sa);
            Long nb = asLong(sb);
            int cmp = (na != null && nb != null) ? Long.compare(na, nb) : sa.compareTo(sb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** True when {@code version} is at or above {@code target} — i.e. applying {@code target} would not be an upgrade. */
    public static boolean atOrAbove(String version, String target) {
        return version != null && target != null && compare(version, target) >= 0;
    }

    /** The numeric core split on '.', with any {@code -qualifier} peeled off first. */
    private static String[] core(String v) {
        if (v == null || v.isBlank()) {
            return new String[] {"0"};
        }
        String t = v.trim();
        int dash = t.indexOf('-');
        String head = dash < 0 ? t : t.substring(0, dash);
        return head.isEmpty() ? new String[] {"0"} : head.split("\\.");
    }

    private static Long asLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
