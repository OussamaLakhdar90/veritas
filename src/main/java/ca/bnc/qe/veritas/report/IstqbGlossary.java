package ca.bnc.qe.veritas.report;

import java.util.Locale;
import java.util.Map;

/**
 * Canonical, deterministic explanations of the ISTQB concepts Veritas cites. Used by the Strategy Rationale
 * document (and reusable by the test-plan / contract L6 note) so the same principle is explained <b>identically
 * and correctly everywhere</b> — the model never has to (and must not) invent the definition. Cite by NAMED
 * concept (e.g. "CTAL-TM — Risk-Based Testing"); the syllabus prefix is stripped on lookup.
 */
public final class IstqbGlossary {

    private IstqbGlossary() {
    }

    private static final Map<String, String> DEFS = Map.ofEntries(
            Map.entry("risk-based testing",
                    "Test effort is allocated in proportion to product and project risk (likelihood × impact), so the "
                            + "highest-risk areas receive the deepest, earliest and most redundant coverage and low-risk "
                            + "areas are tested lightly."),
            Map.entry("boundary value analysis",
                    "Defects cluster at the edges of equivalence partitions; testing the minimum, maximum and the values "
                            + "just inside/outside each range exposes off-by-one and limit errors efficiently."),
            Map.entry("equivalence partitioning",
                    "Inputs are divided into classes the system should treat identically; one representative value per "
                            + "partition gives broad coverage with few cases."),
            Map.entry("decision table testing",
                    "Combinations of conditions and their resulting actions are tabulated so every business rule is "
                            + "covered — ideal for role × state authorization logic."),
            Map.entry("state transition testing",
                    "The system is modelled as states and the events that move between them; tests exercise valid "
                            + "transitions and attempt invalid ones to catch illegal state changes."),
            Map.entry("test levels",
                    "Testing is organised into levels (component, integration, system, acceptance), each with its own "
                            + "objectives and test basis, so defects are caught at the cheapest appropriate stage."),
            Map.entry("test types",
                    "Test types group tests by objective — functional, non-functional (performance, security, "
                            + "usability…) and structural — so quality is assessed across all relevant dimensions."),
            Map.entry("exit criteria",
                    "Measurable conditions that must hold before testing is considered complete (e.g. risk-coverage %, "
                            + "no open critical defects); they make 'done' objective rather than subjective."),
            Map.entry("entry criteria",
                    "Preconditions that must be met before a test activity starts (environment ready, smoke tests pass), "
                            + "preventing wasted effort on an untestable build."),
            Map.entry("test basis",
                    "The body of knowledge tests are derived from (requirements, specs, stories, code, risks); a weak or "
                            + "missing basis means cases cannot be derived reliably."),
            Map.entry("test estimation",
                    "Effort is estimated from evidence (e.g. three-point/PERT: (optimistic + 4×likely + pessimistic)/6) "
                            + "rather than guesswork, producing a defensible schedule."),
            Map.entry("quality characteristics",
                    "ISO/IEC 25010 defines product-quality characteristics (functional suitability, security, "
                            + "reliability, performance…); coverage is assessed against each relevant characteristic."),
            Map.entry("test automation",
                    "Automating repeatable, stable, high-value checks frees human effort for exploratory and "
                            + "judgement-based testing; not everything should be automated — the pyramid favours lower levels."),
            Map.entry("test scope",
                    "What is and isn't being tested, with objectives and assumptions stated explicitly so coverage "
                            + "expectations are unambiguous."));

    /** Explanation for a named concept (syllabus prefix tolerated), or null if not in the glossary. */
    public static String explain(String concept) {
        if (concept == null) {
            return null;
        }
        return DEFS.get(normalize(concept));
    }

    /** Normalise "CTAL-TM — Risk-Based Testing" → "risk-based testing". */
    static String normalize(String concept) {
        String c = concept;
        int dash = c.lastIndexOf('—');                 // em dash separates syllabus from concept
        if (dash < 0) {
            dash = c.lastIndexOf(" - ");               // tolerate hyphen form
            if (dash >= 0) {
                dash += 1;
            }
        }
        if (dash >= 0) {
            c = c.substring(dash + 1);
        }
        return c.trim().toLowerCase(Locale.ROOT);
    }
}
