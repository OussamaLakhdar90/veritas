package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The glossary gives accurate, deterministic definitions and tolerates the "SYLLABUS — Concept" citation form. */
class IstqbGlossaryTest {

    @Test
    void explainsKnownConceptsAndStripsSyllabusPrefix() {
        assertThat(IstqbGlossary.explain("CTAL-TM — Risk-Based Testing"))
                .isNotNull().contains("proportion to product and project risk");
        assertThat(IstqbGlossary.explain("Boundary Value Analysis")).contains("edges of equivalence partitions");
        assertThat(IstqbGlossary.explain("CTAL-TA — Decision Table Testing")).contains("Combinations of conditions");
    }

    @Test
    void unknownConceptReturnsNull() {
        assertThat(IstqbGlossary.explain("Vibes-Based Testing")).isNull();
    }
}
