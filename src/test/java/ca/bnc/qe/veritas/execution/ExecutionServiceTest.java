package ca.bnc.qe.veritas.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import ca.bnc.qe.veritas.integration.xray.XrayClient;
import ca.bnc.qe.veritas.integration.xray.XrayRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Reads Xray run statuses → normalised pass/fail/blocked/not-run + deviations + a plain-language verdict. */
@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    @Mock XrayClient xray;

    @Test
    void summarisesExecutionWithDeviationsAndAttentionVerdict() {
        when(xray.getTestRunStatuses("project = CIAM")).thenReturn(List.of(
                new XrayRunStatus("CIAM-1", "PASS"),
                new XrayRunStatus("CIAM-2", "PASSED"),
                new XrayRunStatus("CIAM-3", "FAIL"),
                new XrayRunStatus("CIAM-4", "BLOCKED"),
                new XrayRunStatus("CIAM-5", "TODO")));

        ExecutionSummary s = new ExecutionService(xray).completion("ciam", "project = CIAM", "alice");

        assertThat(s.total()).isEqualTo(5);
        assertThat(s.passed()).isEqualTo(2);
        assertThat(s.failed()).isEqualTo(1);
        assertThat(s.blocked()).isEqualTo(1);
        assertThat(s.notRun()).isEqualTo(1);
        assertThat(s.deviations()).extracting(ExecutionSummary.TestOutcome::testKey)
                .containsExactlyInAnyOrder("CIAM-3", "CIAM-4");   // failed + blocked
        assertThat(s.verdict()).contains("NEEDS ATTENTION");
    }

    @Test
    void allPassedVerdictWhenEverythingPasses() {
        when(xray.getTestRunStatuses("jql")).thenReturn(List.of(
                new XrayRunStatus("A", "PASS"), new XrayRunStatus("B", "PASS")));
        ExecutionSummary s = new ExecutionService(xray).completion("svc", "jql", "api");
        assertThat(s.deviations()).isEmpty();
        assertThat(s.verdict()).contains("ALL PASSED");
    }

    @Test
    void noExecutedTestsGivesAClearVerdict() {
        when(xray.getTestRunStatuses("jql")).thenReturn(List.of());
        ExecutionSummary s = new ExecutionService(xray).completion("svc", "jql", "api");
        assertThat(s.total()).isZero();
        assertThat(s.verdict()).contains("No executed tests found");
    }

    @Test
    void normaliseMapsCommonStatuses() {
        assertThat(ExecutionService.normalise("PASS")).isEqualTo("PASSED");
        assertThat(ExecutionService.normalise("Failed")).isEqualTo("FAILED");
        assertThat(ExecutionService.normalise("ABORTED")).isEqualTo("BLOCKED");
        assertThat(ExecutionService.normalise("To Do")).isEqualTo("NOT_RUN");
        assertThat(ExecutionService.normalise(null)).isEqualTo("NOT_RUN");
    }
}
