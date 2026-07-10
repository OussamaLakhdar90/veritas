package ca.bnc.qe.veritas.evolve;

import java.util.Map;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;

/**
 * A computed classification proposal for a not-yet-classified ({@code UNSPECIFIED}) {@link FindingType}: the AI's
 * rubric-based severity suggestion + rationale, backed by the field evidence (human votes across services). A
 * maintainer reviews it and either accepts or challenges it (overriding the severity with a comment) before the
 * promotion PR is opened. The engine's breaking-ness is type-derived and never part of this proposal — an override
 * can never hide a consumer-breaking change.
 *
 * @param aiSuggested   {@code true} when the LLM applied the rubric; {@code false} when it was offline and the
 *                      suggestion defaulted to the field consensus.
 * @param voteCount     total human classification votes (distinct fingerprints) behind this type.
 * @param voteBreakdown the evidence — how many votes each human-chosen severity received.
 */
public record ClassificationProposal(
        FindingType findingType,
        Severity suggestedSeverity,
        boolean aiSuggested,
        String rationale,
        int voteCount,
        int distinctServices,
        Map<Severity, Integer> voteBreakdown) {
}
