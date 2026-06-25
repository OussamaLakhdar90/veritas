package ca.bnc.qe.veritas.evidence.feature;

import java.util.List;

/**
 * One coverage gap found deterministically over the feature index — a citable RTM signal.
 *
 * @param kind        what kind of gap
 * @param featureId   the feature it concerns
 * @param message     a plain, reviewer-facing description
 * @param citedUnitIds the evidence unit ids that justify it (for the why-doc / traceability)
 */
public record Gap(GapKind kind, String featureId, String message, List<String> citedUnitIds) {

    public Gap {
        citedUnitIds = citedUnitIds == null ? List.of() : List.copyOf(citedUnitIds);
    }
}
