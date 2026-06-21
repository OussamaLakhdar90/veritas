package ca.bnc.qe.veritas.coverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Tiered, deterministic coverage matcher: pairs each required test case with an existing test by normalized
 * title (exact → HIGH, containment → MEDIUM), else marks it a GAP. (LLM semantic matching for the uncertain
 * middle is a later enhancement; this is the free, deterministic tier.)
 */
@Component
public class CoverageMatcher {

    public List<Match> match(List<String> requiredTitles, List<TitledTest> existing) {
        List<Match> matches = new ArrayList<>();
        for (String required : requiredTitles) {
            String n = norm(required);
            String key = null;
            String confidence = null;
            for (TitledTest t : existing) {
                if (norm(t.title()).equals(n) && !n.isEmpty()) {
                    key = t.key();
                    confidence = "HIGH";
                    break;
                }
            }
            if (key == null) {
                for (TitledTest t : existing) {
                    String tn = norm(t.title());
                    if (!tn.isEmpty() && (tn.contains(n) || n.contains(tn))) {
                        key = t.key();
                        confidence = "MEDIUM";
                        break;
                    }
                }
            }
            matches.add(new Match(required, key != null ? "MATCHED" : "GAP", key, key != null ? confidence : "HIGH"));
        }
        return matches;
    }

    private String norm(String s) {
        return fingerprint(s);
    }

    /** Stable normalized fingerprint of a test title — shared with dedup so equal titles never double-create. */
    public static String fingerprint(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    public record Match(String requiredTitle, String status, String matchedKey, String confidence) {}

    public record TitledTest(String key, String title) {}
}
