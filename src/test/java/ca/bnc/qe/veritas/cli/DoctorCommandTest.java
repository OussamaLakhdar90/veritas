package ca.bnc.qe.veritas.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import ca.bnc.qe.veritas.preflight.ConfigDoctor;
import ca.bnc.qe.veritas.preflight.ConfigDoctor.Check;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Branch coverage for {@link DoctorCommand} driven through picocli's {@code execute()} with a mocked
 * {@link ConfigDoctor}. Covers the report rendering (detail/remediation present vs null vs blank),
 * the MISSING-count branch, and both arms of the final ready/missing summary line. Standard out is
 * captured so the rendered lines are asserted on real text, not just the exit code.
 */
class DoctorCommandTest {

    private final ConfigDoctor doctor = mock(ConfigDoctor.class);

    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void redirectStdout() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(realOut);
    }

    private int run() {
        // No args; the command takes none, picocli just invokes call().
        return new CommandLine(new DoctorCommand(doctor)).execute();
    }

    private String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------------------------------

    @Test
    void emptyReportPrintsHeaderAndReadyAndReturnsZero() {
        when(doctor.report()).thenReturn(List.of());

        assertThat(run()).isZero();

        String text = out();
        assertThat(text).startsWith("Veritas configuration check:");
        // No MISSING items at all → the "Ready" arm of the final ternary.
        assertThat(text).contains("Ready. All required configuration is present.");
        assertThat(text).doesNotContain("required item(s) missing");
        verify(doctor).report();
    }

    @Test
    void allOkReportRendersEveryCheckLineAndStaysReady() {
        // Two OK checks, each with a detail; remediation is empty (blank) so the arrow line is skipped.
        when(doctor.report()).thenReturn(List.of(
                new Check("Git access (clone)", "OK", "Configured.", ""),
                new Check("Jira base URL", "OK", "https://jira.example", "")));

        assertThat(run()).isZero();

        String text = out();
        // The "[%-7s]" format left-pads the status to width 7 → "OK     " (OK + 5 spaces).
        assertThat(text).contains("[OK     ] Git access (clone)");
        assertThat(text).contains("[OK     ] Jira base URL");
        // details are indented and printed
        assertThat(text).contains("            Configured.");
        assertThat(text).contains("            https://jira.example");
        // blank remediation → no arrow line emitted
        assertThat(text).doesNotContain("→");
        assertThat(text).contains("Ready. All required configuration is present.");
    }

    @Test
    void missingChecksAreCountedAndDriveTheMissingSummaryWithRemediationArrows() {
        // Two MISSING (each contributes to the count + prints a remediation arrow) and one OK in between.
        when(doctor.report()).thenReturn(List.of(
                new Check("Git access (clone)", "MISSING", "Not set.", "Set the GIT_TOKEN secret."),
                new Check("Jira base URL", "OK", "https://jira", ""),
                new Check("Jira token", "MISSING", "Not set.", "Set the JIRA_API_TOKEN secret.")));

        assertThat(run()).isZero();   // call() always returns 0 regardless of missing count

        String text = out();
        assertThat(text).contains("[MISSING] Git access (clone)");
        assertThat(text).contains("[OK     ] Jira base URL");
        assertThat(text).contains("[MISSING] Jira token");
        // remediation arrows for the two missing items
        assertThat(text).contains("            → Set the GIT_TOKEN secret.");
        assertThat(text).contains("            → Set the JIRA_API_TOKEN secret.");
        // exactly two missing → the count-bearing summary line, NOT the ready line.
        assertThat(text).contains("2 required item(s) missing — see the arrows above. Details: docs/configuration.md");
        assertThat(text).doesNotContain("Ready. All required configuration is present.");
    }

    @Test
    void singleMissingProducesCountOfOne() {
        when(doctor.report()).thenReturn(List.of(
                new Check("Git access (clone)", "MISSING", "Not set.", "Set the GIT_TOKEN secret.")));

        assertThat(run()).isZero();

        String text = out();
        assertThat(text).contains("1 required item(s) missing");
        assertThat(text).doesNotContain("2 required item(s) missing");
        assertThat(text).doesNotContain("Ready.");
    }

    @Test
    void nullDetailAndNullRemediationSkipBothIndentedLines() {
        // null detail and null remediation → both inner blocks are skipped (the != null guards).
        when(doctor.report()).thenReturn(List.of(
                new Check("Confluence base URL", "WARN", null, null)));

        assertThat(run()).isZero();

        String text = out();
        assertThat(text).contains("[WARN   ] Confluence base URL");
        // no indented detail line and no arrow line for this check
        assertThat(text).doesNotContain("            ");
        assertThat(text).doesNotContain("→");
        // WARN is not MISSING → still "Ready".
        assertThat(text).contains("Ready. All required configuration is present.");
    }

    @Test
    void blankDetailAndBlankRemediationAreTreatedAsAbsent() {
        // "   " (whitespace only) is blank → both isBlank() guards short-circuit the prints.
        when(doctor.report()).thenReturn(List.of(
                new Check("LLM gateway", "WARN", "   ", "   ")));

        assertThat(run()).isZero();

        String text = out();
        assertThat(text).contains("[WARN   ] LLM gateway");
        assertThat(text).doesNotContain("→");
        // the blank detail must not appear as its own indented line
        assertThat(text).doesNotContain("\n               \n");
        assertThat(text).contains("Ready. All required configuration is present.");
    }

    @Test
    void detailPresentButRemediationBlankPrintsDetailLineOnly() {
        // Distinguishes the detail-guard branch from the remediation-guard branch independently.
        when(doctor.report()).thenReturn(List.of(
                new Check("Xray token", "OK", "Xray edition=SERVER_DC", "")));

        assertThat(run()).isZero();

        String text = out();
        assertThat(text).contains("[OK     ] Xray token");
        assertThat(text).contains("            Xray edition=SERVER_DC");
        assertThat(text).doesNotContain("→");
    }

    @Test
    void detailBlankButRemediationPresentPrintsArrowLineOnly() {
        // The mirror case: blank detail (skipped) but a real remediation (printed).
        when(doctor.report()).thenReturn(List.of(
                new Check("Copilot (GitHub device-flow auth)", "MISSING", "",
                        "Run `veritas copilot-login` to authorize this app.")));

        assertThat(run()).isZero();

        String text = out();
        assertThat(text).contains("[MISSING] Copilot (GitHub device-flow auth)");
        assertThat(text).contains("            → Run `veritas copilot-login` to authorize this app.");
        assertThat(text).contains("1 required item(s) missing");
    }
}