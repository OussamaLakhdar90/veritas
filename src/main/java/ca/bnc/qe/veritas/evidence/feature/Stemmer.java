package ca.bnc.qe.veritas.evidence.feature;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A deliberately tiny morphological stemmer used only to canonicalise clustering hints, so deterministic seeding
 * matches simple plural/singular variants ({@code policies}→{@code policy}, {@code logins}→{@code login}) without
 * an LLM. It does NOT handle semantic synonyms (login/authentication) — that's the (later) LLM tagger's job. Being
 * over- or under-aggressive only affects bucketing, never correctness, so a small rule set is fine.
 */
public final class Stemmer {

    private Stemmer() {
    }

    public static String stem(String token) {
        if (token == null) {
            return "";
        }
        String t = token.toLowerCase(java.util.Locale.ROOT);
        if (t.length() > 4 && t.endsWith("ies")) {
            return t.substring(0, t.length() - 3) + "y";          // policies → policy
        }
        if (t.length() > 4 && (t.endsWith("ses") || t.endsWith("xes") || t.endsWith("zes")
                || t.endsWith("ches") || t.endsWith("shes"))) {
            return t.substring(0, t.length() - 2);                // boxes → box, classes → class
        }
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss") && !t.endsWith("us")) {
            return t.substring(0, t.length() - 1);                // logins → login (not "status"/"class")
        }
        return t;
    }

    /** Stem each hint in a set (deduplicated). */
    public static Set<String> stemAll(Set<String> hints) {
        Set<String> out = new LinkedHashSet<>();
        if (hints != null) {
            for (String h : hints) {
                out.add(stem(h));
            }
        }
        return out;
    }
}
