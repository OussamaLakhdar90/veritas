package ca.bnc.qe.veritas.engine.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * Branch-maximising unit tests for {@link SpecPresence}: the {@code empty()} factory, the four boolean
 * rollups, {@code merge()} (including the null short-circuit and each per-field OR), and every branch of
 * {@code contradictsAbsenceClaim} — the absence-phrasing detector, each claim-keyword bucket, the
 * "no tracked claim" early-out, and each "keep the finding because one claim is genuinely true" guard.
 */
class SpecPresenceBranchTest {

    private static SpecPresence all() {
        return new SpecPresence(true, true, true, true);
    }

    // ---------------------------------------------------------------------------------------------
    // empty() + accessors
    // ---------------------------------------------------------------------------------------------

    @Test
    void emptyHasAllFalse() {
        SpecPresence p = SpecPresence.empty();
        assertThat(p.anyResponseHasExamples()).isFalse();
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isFalse();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    @Test
    void accessorsReturnEachComponentDistinctly() {
        SpecPresence p = new SpecPresence(true, false, true, false);
        assertThat(p.anyResponseHasExamples()).isTrue();
        assertThat(p.anySchemaHasProperties()).isFalse();
        assertThat(p.anySchemaHasConstraints()).isTrue();
        assertThat(p.anyErrorResponseDeclared()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // merge()
    // ---------------------------------------------------------------------------------------------

    @Nested
    class Merge {

        @Test
        void mergeWithNullReturnsSameInstance() {
            SpecPresence p = new SpecPresence(true, false, true, false);
            assertThat(p.merge(null)).isSameAs(p);
        }

        @Test
        void mergeEmptyWithEmptyStaysAllFalse() {
            SpecPresence m = SpecPresence.empty().merge(SpecPresence.empty());
            assertThat(m).isEqualTo(SpecPresence.empty());
            assertThat(m.anyResponseHasExamples()).isFalse();
            assertThat(m.anySchemaHasProperties()).isFalse();
            assertThat(m.anySchemaHasConstraints()).isFalse();
            assertThat(m.anyErrorResponseDeclared()).isFalse();
        }

        @Test
        void mergeOrsExamplesFromLeftOnly() {
            SpecPresence m = new SpecPresence(true, false, false, false)
                    .merge(new SpecPresence(false, false, false, false));
            assertThat(m.anyResponseHasExamples()).isTrue();
            assertThat(m.anySchemaHasProperties()).isFalse();
            assertThat(m.anySchemaHasConstraints()).isFalse();
            assertThat(m.anyErrorResponseDeclared()).isFalse();
        }

        @Test
        void mergeOrsExamplesFromRightOnly() {
            SpecPresence m = new SpecPresence(false, false, false, false)
                    .merge(new SpecPresence(true, false, false, false));
            assertThat(m.anyResponseHasExamples()).isTrue();
        }

        @Test
        void mergeOrsPropertiesFromRightOnly() {
            SpecPresence m = new SpecPresence(false, false, false, false)
                    .merge(new SpecPresence(false, true, false, false));
            assertThat(m.anySchemaHasProperties()).isTrue();
            assertThat(m.anyResponseHasExamples()).isFalse();
            assertThat(m.anySchemaHasConstraints()).isFalse();
            assertThat(m.anyErrorResponseDeclared()).isFalse();
        }

        @Test
        void mergeOrsConstraintsFromLeftOnly() {
            SpecPresence m = new SpecPresence(false, false, true, false)
                    .merge(new SpecPresence(false, false, false, false));
            assertThat(m.anySchemaHasConstraints()).isTrue();
            assertThat(m.anyResponseHasExamples()).isFalse();
            assertThat(m.anySchemaHasProperties()).isFalse();
            assertThat(m.anyErrorResponseDeclared()).isFalse();
        }

        @Test
        void mergeOrsErrorsFromRightOnly() {
            SpecPresence m = new SpecPresence(false, false, false, false)
                    .merge(new SpecPresence(false, false, false, true));
            assertThat(m.anyErrorResponseDeclared()).isTrue();
            assertThat(m.anyResponseHasExamples()).isFalse();
            assertThat(m.anySchemaHasProperties()).isFalse();
            assertThat(m.anySchemaHasConstraints()).isFalse();
        }

        @Test
        void mergeUnionsAllFourFieldsAcrossComplementaryHalves() {
            SpecPresence left = new SpecPresence(true, false, true, false);
            SpecPresence right = new SpecPresence(false, true, false, true);
            SpecPresence m = left.merge(right);
            assertThat(m).isEqualTo(all());
        }

        @Test
        void mergeIsTrueWhenBothSidesTrue() {
            SpecPresence m = all().merge(all());
            assertThat(m).isEqualTo(all());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // contradictsAbsenceClaim — early outs
    // ---------------------------------------------------------------------------------------------

    @Test
    void nullSummaryNeverContradicts() {
        assertThat(all().contradictsAbsenceClaim(null)).isFalse();
    }

    @Test
    void summaryWithoutAbsenceWordingNeverContradicts() {
        // contains "example" (a tracked claim) but no absence phrasing -> assertsAbsence false -> early out
        assertThat(all().contradictsAbsenceClaim("Inline response example present for all endpoints")).isFalse();
    }

    @Test
    void absenceWordingButNoTrackedClaimReturnsFalse() {
        // "missing" asserts absence, but none of example/propert/field/constraint/error are mentioned
        assertThat(all().contradictsAbsenceClaim("Operation is missing an operationId")).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // contradictsAbsenceClaim — absence-phrasing detector (each disjunct of assertsAbsence)
    // Each summary mentions "examples" (tracked) so only the absence-word differs.
    // ---------------------------------------------------------------------------------------------

    @Test
    void absenceWordNoSpaceTriggers() {
        assertThat(all().contradictsAbsenceClaim("There are no examples")).isTrue();
    }

    @Test
    void absenceWordNotSpaceTriggers() {
        assertThat(all().contradictsAbsenceClaim("Examples are not provided")).isTrue();
    }

    @Test
    void absenceWordWithoutTriggers() {
        assertThat(all().contradictsAbsenceClaim("Endpoints documented without examples")).isTrue();
    }

    @Test
    void absenceWordMissingTriggers() {
        assertThat(all().contradictsAbsenceClaim("Response examples missing")).isTrue();
    }

    @Test
    void absenceWordAbsentTriggers() {
        assertThat(all().contradictsAbsenceClaim("Examples absent from responses")).isTrue();
    }

    @Test
    void absenceWordLacksTriggers() {
        assertThat(all().contradictsAbsenceClaim("Spec lacks examples")).isTrue();
    }

    @Test
    void absenceWordNoneTriggers() {
        // "none" is a substring disjunct; pair it with a tracked claim word that itself is not an absence word
        assertThat(all().contradictsAbsenceClaim("Examples: none documented")).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // contradictsAbsenceClaim — claim-keyword buckets (with absence phrasing present)
    // ---------------------------------------------------------------------------------------------

    @Test
    void exampleClaimContradictedWhenExamplesPresent() {
        SpecPresence p = new SpecPresence(true, false, false, false);
        assertThat(p.contradictsAbsenceClaim("No examples")).isTrue();
    }

    @Test
    void propertClaimKeywordContradictedWhenPropertiesPresent() {
        SpecPresence p = new SpecPresence(false, true, false, false);
        assertThat(p.contradictsAbsenceClaim("Schema has no properties")).isTrue();
    }

    @Test
    void fieldClaimKeywordContradictedWhenPropertiesPresent() {
        SpecPresence p = new SpecPresence(false, true, false, false);
        // uses "field" rather than "propert" to hit the other half of the OR
        assertThat(p.contradictsAbsenceClaim("No fields defined on the model")).isTrue();
    }

    @Test
    void constraintClaimContradictedWhenConstraintsPresent() {
        // avoid the words "propert"/"field" so only the constraint claim is in play
        SpecPresence p = new SpecPresence(false, false, true, false);
        assertThat(p.contradictsAbsenceClaim("No validation constraints declared")).isTrue();
    }

    @Test
    void errorResponsePhraseContradictedWhenErrorsPresent() {
        SpecPresence p = new SpecPresence(false, false, false, true);
        assertThat(p.contradictsAbsenceClaim("No error response declared")).isTrue();
    }

    @Test
    void errorCodePhraseContradictedWhenErrorsPresent() {
        SpecPresence p = new SpecPresence(false, false, false, true);
        assertThat(p.contradictsAbsenceClaim("Missing error code documentation")).isTrue();
    }

    @Test
    void errorStatusPhraseContradictedWhenErrorsPresent() {
        SpecPresence p = new SpecPresence(false, false, false, true);
        assertThat(p.contradictsAbsenceClaim("No error status codes present")).isTrue();
    }

    @Test
    void barewordErrorWithoutTrackedPhraseIsNotAnErrorClaim() {
        // "error" alone (not "error response/code/status") is not a tracked error claim, and no other
        // tracked keyword appears -> falls into the "no tracked claim" early-out -> false.
        SpecPresence p = new SpecPresence(false, false, false, true);
        assertThat(p.contradictsAbsenceClaim("No error handling guidance")).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // contradictsAbsenceClaim — "keep the finding" guards (a genuinely-true claim survives)
    // ---------------------------------------------------------------------------------------------

    @Test
    void exampleClaimKeptWhenSpecGenuinelyHasNoExamples() {
        SpecPresence p = new SpecPresence(false, true, true, true);
        assertThat(p.contradictsAbsenceClaim("No examples present")).isFalse();
    }

    @Test
    void propertyClaimKeptWhenSpecGenuinelyHasNoProperties() {
        SpecPresence p = new SpecPresence(true, false, true, true);
        assertThat(p.contradictsAbsenceClaim("Schema declares no properties")).isFalse();
    }

    @Test
    void constraintClaimKeptWhenSpecGenuinelyHasNoConstraints() {
        SpecPresence p = new SpecPresence(true, true, false, true);
        assertThat(p.contradictsAbsenceClaim("No constraints declared")).isFalse();
    }

    @Test
    void errorClaimKeptWhenSpecGenuinelyHasNoErrors() {
        SpecPresence p = new SpecPresence(true, true, true, false);
        assertThat(p.contradictsAbsenceClaim("No error responses documented")).isFalse();
    }

    @Test
    void multiClaimKeptIfAnySingleClaimIsTrueEvenWhenOthersContradicted() {
        // Spec HAS examples/properties/constraints (those claims are false) but genuinely has NO errors.
        // The error claim is true -> whole finding is kept (not contradicted).
        SpecPresence p = new SpecPresence(true, true, true, false);
        assertThat(p.contradictsAbsenceClaim(
                "Weak spec: no examples, no properties, no constraints, and no error responses")).isFalse();
    }

    @Test
    void multiClaimContradictedOnlyWhenEveryClaimIsFalse() {
        SpecPresence p = all();
        assertThat(p.contradictsAbsenceClaim(
                "Weak spec: no examples, no fields, no constraints, and no error responses")).isTrue();
    }

    @Test
    void contradictsIsCaseInsensitive() {
        assertThat(all().contradictsAbsenceClaim("NO EXAMPLES ARE PROVIDED")).isTrue();
    }
}
