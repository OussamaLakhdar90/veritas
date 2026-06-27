package ca.bnc.qe.veritas.report;

import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Enforces ISTQB knowledge-pack §0.1 — <i>cite by NAMED concept, never a section number</i> — deterministically.
 * The prompts only ASK for it, so a real model can still leak a syllabus paragraph number into a citation; this
 * strips the leaked "§1.3.3" / trailing "1.3.3" reference, leaving the named concept
 * (e.g. {@code "CTAL-TM — Risk-Based Testing §1.3.3"} → {@code "CTAL-TM — Risk-Based Testing"}). It only removes
 * unambiguous section markers, so a legitimate named-concept citation is left untouched.
 */
@Component
public class CitationSanitizer {

    /** A "§" marker followed by a (possibly dotted) number, optionally parenthesised. */
    private static final Pattern SECTION_MARKER = Pattern.compile("\\s*\\(?§\\s*\\d+(?:\\.\\d+)*\\)?");
    /** A trailing dotted section number with no "§" (e.g. "… 1.3.3") — requires a dot, so a bare "3" is kept. */
    private static final Pattern TRAILING_DOTTED = Pattern.compile("\\s+\\d+(?:\\.\\d+)+\\s*$");

    public String strip(String citation) {
        if (citation == null) {
            return null;
        }
        String c = SECTION_MARKER.matcher(citation).replaceAll("");
        c = TRAILING_DOTTED.matcher(c).replaceAll("");
        return c.strip();
    }

    /** Sanitize the citation fields of a strategy/plan deliverable in place (riskRegister, techniques, exitCriteria). */
    public void sanitizeDeliverable(ObjectNode deliverable) {
        if (deliverable == null) {
            return;
        }
        sanitizeArrayField(deliverable.path("riskRegister"), "citation");
        sanitizeArrayField(deliverable.path("testApproach").path("techniques"), "citation");
        sanitizeArrayField(deliverable.path("exitCriteria"), "citation");
    }

    /** Strip section refs from a named string field on every object of a JSON array (no-op when absent). */
    public void sanitizeArrayField(JsonNode array, String field) {
        if (array != null && array.isArray()) {
            for (JsonNode n : array) {
                if (n instanceof ObjectNode o && o.hasNonNull(field)) {
                    o.put(field, strip(o.path(field).asText()));
                }
            }
        }
    }
}
