package ca.bnc.qe.veritas.evolve;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClassificationPollerTest {

    private final EngineEvolutionService service = mock(EngineEvolutionService.class);
    private final ClassificationPoller poller = new ClassificationPoller(service);

    @Test
    void pollRecomputesProposals() {
        when(service.refresh("scheduler")).thenReturn(List.of());
        poller.poll();
        verify(service).refresh("scheduler");
    }

    @Test
    void pollSwallowsFailuresSoTheSchedulerKeepsRunning() {
        when(service.refresh("scheduler")).thenThrow(new RuntimeException("boom"));
        poller.poll();   // must not propagate
        verify(service).refresh("scheduler");
    }
}
