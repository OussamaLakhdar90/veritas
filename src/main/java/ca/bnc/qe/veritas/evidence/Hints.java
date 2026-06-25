package ca.bnc.qe.veritas.evidence;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Cheap, deterministic clustering signals attached to each {@link EvidenceUnit} so the (later) {@code FeatureSeeder}
 * can bucket related units by overlap before the LLM canonicalises feature names. These are a rough first pass —
 * "policy" vs "policies" won't match here; the tagger handles synonyms (design §3). Jira labels/components are a
 * richer signal added by the Jira field-widening follow-up; until then we derive hints from titles and paths.
 */
public final class Hints {

    private Hints() {
    }

    // Common API/English noise that carries no clustering signal.
    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "from", "into", "this", "that", "api", "service", "endpoint",
            "get", "post", "put", "patch", "delete", "request", "response", "returns", "should", "must", "via");

    /** Significant lowercase tokens (≥3 chars, not stop-words) from free text — a baseline title/summary hint. */
    public static Set<String> fromText(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String tok : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tok.length() >= 3 && !STOP.contains(tok)) {
                out.add(tok);
            }
        }
        return out;
    }

    /** Path-nouns from a URL template — the literal segments, skipping {path vars} and stop-words. */
    public static Set<String> fromPath(String pathTemplate) {
        Set<String> out = new LinkedHashSet<>();
        if (pathTemplate == null) {
            return out;
        }
        for (String seg : pathTemplate.split("/")) {
            if (seg.isBlank() || seg.startsWith("{")) {
                continue;
            }
            for (String tok : seg.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (tok.length() >= 3 && !STOP.contains(tok)) {
                    out.add(tok);
                }
            }
        }
        return out;
    }
}
