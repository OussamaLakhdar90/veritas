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
    void everyFindingTypeHasAnExplicitSeverityNoneFallsThroughToUnspecified() {
        var unclassified = Arrays.stream(FindingType.values())
                .filter(t -> DiffEngine.severityOf(t) == Severity.UNSPECIFIED)
                .map(Enum::name)
                .toList();
        assertThat(unclassified)
                .as("These FindingType(s) have no explicit severity in DiffEngine.severityOf and would fail SAFE to "
                        + "UNSPECIFIED at runtime — classify each into a BLOCKER/CRITICAL/MAJOR/MINOR/INFO case.")
                .isEmpty();
    }
}
