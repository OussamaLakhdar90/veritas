package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import ca.bnc.qe.veritas.codegen.GeneratedFileWriter;
import ca.bnc.qe.veritas.codegen.PrPublisher;
import ca.bnc.qe.veritas.skill.NotFoundException;
import ca.bnc.qe.veritas.vcs.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The synchronous guards on the async fix runner: dedup on submit + the confirm-phase preconditions. */
class AsyncSnykFixRunnerTest {

    private final SnykFixTrainRepository trains = mock(SnykFixTrainRepository.class);
    private final SnykFixStepRepository steps = mock(SnykFixStepRepository.class);

    private final AsyncSnykFixRunner runner = new AsyncSnykFixRunner(
            mock(WorkspaceService.class), mock(CascadePlanner.class), mock(CascadeVerifier.class),
            mock(BreakingChangeService.class), mock(SnykFixJiraService.class), mock(SnykFixActions.class),
            mock(ReviewerSuggester.class), mock(GeneratedFileWriter.class), mock(PrPublisher.class),
            new FrameworkProperties(), trains, steps, new ObjectMapper());

    private SnykFixRequest request() {
        return new SnykFixRequest("w1", "i1", "com.x:y", "1.0", "2.0", "critical",
                List.of("APP7576"), null, "CIAM", "Task", List.of(), "alice", false);
    }

    private SnykFixTrain train(String id, String status) {
        SnykFixTrain t = new SnykFixTrain();
        t.setId(id);
        t.setStatus(status);
        return t;
    }

    @Test
    void submitReusesAnInFlightTrainForTheSameWatchAndCoordinate() {
        when(trains.findByWatchIdAndCoordinate("w1", "com.x:y"))
                .thenReturn(List.of(train("existing", SnykFixStatus.VERIFYING)));

        String id = runner.submit(request());

        assertThat(id).isEqualTo("existing");
        verify(trains, never()).save(any());   // no duplicate train row created / no background run kicked off
    }

    @Test
    void confirmRejectsATrainThatIsNotAwaitingConfirmation() {
        when(trains.findById("t1")).thenReturn(Optional.of(train("t1", SnykFixStatus.PLANNING)));
        assertThatThrownBy(() -> runner.confirm("t1", Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        verify(trains, never()).save(any());
    }

    @Test
    void confirmThrowsNotFoundForAnUnknownTrain() {
        when(trains.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> runner.confirm("missing", Map.of(), Map.of()))
                .isInstanceOf(NotFoundException.class);
    }
}
