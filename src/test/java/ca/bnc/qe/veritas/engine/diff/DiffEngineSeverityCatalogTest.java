package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

/**
 * The severity catalog must stay COMPLETE: every {@link FindingType} must have an EXPLICIT classification in
 * {@link DiffEngine#severityOf}. {@code UNSPECIFIED} is a runtime fail-safe for a type someone forgot to classify —
 * this test fails the build the moment a new type reaches the {@code default} arm, so an unclassified type can never
 * ship (it would otherwise hold every release carrying that finding at WARN and prompt manual triage).
 */
class DiffEngineSeverityCatalogTest {

    @Test
    void everyFindingTypeHasAnExplicitSeverityExceptTheDeferredAllowlist() {
        var unclassified = Arrays.stream(FindingType.values())
                .filter(t -> DiffEngine.severityOf(t) == Severity.UNSPECIFIED)
                .filter(t -> !DiffEngine.PENDING_CLASSIFICATION.contains(t))
                .map(Enum::name)
                .toList();
        assertThat(unclassified)
                .as("These FindingType(s) have no explicit severity in DiffEngine.severityOf and are NOT on the "
                        + "PENDING_CLASSIFICATION allowlist — classify each into a BLOCKER/CRITICAL/MAJOR/MINOR/INFO "
                        + "case, or (for a new type awaiting field classification) add it to PENDING_CLASSIFICATION.")
                .isEmpty();
    }

    @Test
    void noAllowlistedTypeIsAlreadyClassified() {
        // A promotion PR must REMOVE a type from PENDING_CLASSIFICATION as it adds its severityOf case; a stale entry
        // (allowlisted yet already classified) means the promotion left the allowlist inconsistent.
        var stale = DiffEngine.PENDING_CLASSIFICATION.stream()
                .filter(t -> DiffEngine.severityOf(t) != Severity.UNSPECIFIED)
                .map(Enum::name)
                .toList();
        assertThat(stale)
                .as("These FindingType(s) are on PENDING_CLASSIFICATION but already have an explicit severity — "
                        + "remove them from the allowlist.")
                .isEmpty();
    }
}
