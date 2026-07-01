package ca.bnc.qe.veritas.engine.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;

/**
 * Media-type compatibility per Spring's {@link MediaType} semantics (wildcards and {@code +suffix} ranges), with a
 * base-string fallback. Pure functions, no state — extracted from DiffEngine so the diff engine no longer carries the
 * Spring MediaType parsing concern; the finding-emitting {@code mediaTypeMismatch} stays in DiffEngine and calls this.
 */
final class MediaTypeComparator {

    private MediaTypeComparator() {
    }

    /** Media-type compatibility per Spring's MediaType semantics (a star/star or application-star wildcard, and an
     *  application-star-plus-json range matching application/json) — replaces literal set-equality so a wildcard or
     *  +suffix range is not a false CONSUMES_PRODUCES_MISMATCH. Falls back to base-string equality on a parse failure. */
    static boolean compatible(List<String> code, List<String> specMt) {
        try {
            List<MediaType> cs = parseMedia(code);
            List<MediaType> ss = parseMedia(specMt);
            if (cs.isEmpty() || ss.isEmpty()) {
                return mediaSet(code).equals(mediaSet(specMt));
            }
            return cs.stream().allMatch(c -> ss.stream().anyMatch(s -> s.isCompatibleWith(c)))
                    && ss.stream().allMatch(s -> cs.stream().anyMatch(c -> c.isCompatibleWith(s)));
        } catch (RuntimeException unparseable) {
            return mediaSet(code).equals(mediaSet(specMt));
        }
    }

    /** Lower-cased base media type, parameters stripped: {@code Application/JSON;charset=UTF-8} → {@code application/json}. */
    static String base(String x) {
        if (x == null) {
            return null;
        }
        String b = x.toLowerCase(Locale.ROOT);
        int semi = b.indexOf(';');
        b = (semi >= 0 ? b.substring(0, semi) : b).trim();
        return b.isEmpty() ? null : b;
    }

    private static List<MediaType> parseMedia(List<String> v) {
        List<MediaType> out = new ArrayList<>();
        for (String x : v) {
            if (x != null && !x.isBlank()) {
                out.add(MediaType.parseMediaType(x.trim()));
            }
        }
        return out;
    }

    /** Media types compared by base type, case-insensitive, ignoring parameters (e.g. {@code ;charset=utf-8}). */
    private static Set<String> mediaSet(List<String> v) {
        Set<String> out = new HashSet<>();
        for (String x : v) {
            String b = base(x);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }
}
