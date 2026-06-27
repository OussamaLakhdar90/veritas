package ca.bnc.qe.veritas.evidence.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Deterministic ($0) enforcement of the evidence-first contract on a section's {@code evidence[]} (design §4c).
 * Two checks, both beyond what the JSON schema can express:
 *
 * <ul>
 *   <li><b>Existence / closed-world</b> — every cited {@code unitId} must be in the section's allowed set (the
 *       feature slice + cross-cutting). A hallucinated id (or one from another feature) fails.</li>
 *   <li><b>Quote grounding</b> — a cited {@code quote} must actually be a substring of that unit's text
 *       (whitespace/case-normalised). This closes the "cite a real id but fabricate the claim" hole, since
 *       retrieval already narrowed the allowed ids so a bare existence check is nearly free.</li>
 * </ul>
 *
 * The caller (synthesis) uses the verdict to drop/regenerate a section — the validator never throws.
 */
@Component
public class CitationValidator {

    /** A quote must be at least this many (normalised) chars to actually ground a claim — a 1–2 char fragment
     *  substring-matches almost any text and would defeat the grounding check. */
    static final int MIN_QUOTE_CHARS = 12;

    /** Whether the evidence is valid, plus a human-readable list of every problem (for the regenerate-once feedback). */
    public record Result(boolean valid, List<String> problems) {
        public Result {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    public Result validate(JsonNode evidence, Map<String, EvidenceUnit> unitsById, Set<String> allowedIds) {
        List<String> problems = new ArrayList<>();
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
            return new Result(false, List.of("the section cited no evidence"));
        }
        for (JsonNode item : evidence) {
            String id = item.path("unitId").asText("");
            if (id.isBlank() || !allowedIds.contains(id)) {
                problems.add("cited id '" + id + "' is not in this section's allowed evidence");
                continue;
            }
            EvidenceUnit u = unitsById.get(id);
            if (u == null) {
                // allowed but not resolvable — fail loud rather than silently accept an ungrounded citation.
                problems.add("cited id '" + id + "' has no resolvable evidence unit");
                continue;
            }
            // Every citation MUST carry a grounded quote — a bare id proves nothing about the claim it backs.
            // (The old `if (!quote.isBlank())` skip let a section cite a real id while fabricating the content.)
            String quote = norm(item.path("quote").asText(""));
            if (quote.isBlank()) {
                problems.add("evidence for '" + id + "' has no quote — cite a verbatim phrase from the unit's text "
                        + "so the claim is grounded, not just the id");
            } else if (quote.length() < MIN_QUOTE_CHARS) {
                problems.add("quote for '" + id + "' is too short to ground a claim — cite a verbatim phrase");
            } else if (!norm(u.text()).contains(quote)) {
                problems.add("quote for '" + id + "' is not found in that unit's text");
            }
        }
        return new Result(problems.isEmpty(), problems);
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
