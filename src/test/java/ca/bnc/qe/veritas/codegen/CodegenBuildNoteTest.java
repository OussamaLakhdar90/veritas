package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The PR build-status note is honest: SKIPPED (never compiled) is called out, not dressed up as a pass. */
class CodegenBuildNoteTest {

    @Test
    void buildNoteIsHonestAboutWhatWasAndWasNotVerified() {
        assertThat(CodegenService.buildNote("PASS")).contains("verified locally");
        assertThat(CodegenService.buildNote("REPAIRED")).contains("verified locally");
        assertThat(CodegenService.buildNote("SKIPPED"))
                .contains("NOT compiled").contains("rely on CI");
        assertThat(CodegenService.buildNote("FAIL")).contains("override").contains("did not compile");
        assertThat(CodegenService.buildNote(null)).isNotBlank();
    }
}
