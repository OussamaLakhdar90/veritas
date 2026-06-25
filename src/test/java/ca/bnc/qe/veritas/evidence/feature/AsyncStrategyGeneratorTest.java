package ca.bnc.qe.veritas.evidence.feature;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.persistence.FeatureIndexSnapshot;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import org.junit.jupiter.api.Test;

/** The off-thread strategy worker: success links the strategy back to the snapshot; failure records the error. */
class AsyncStrategyGeneratorTest {

    private FeatureIndexSnapshot claimed(String id) {
        FeatureIndexSnapshot s = new FeatureIndexSnapshot();
        s.setId(id);
        s.setServiceName("ciam-policies");
        s.setOwner("alice");
        return s;
    }

    @Test
    void onSuccessLinksTheGeneratedStrategyBackToTheSnapshot() {
        FeatureIndexSnapshotService snapshots = mock(FeatureIndexSnapshotService.class);
        MultiSourceStrategyService strategyService = mock(MultiSourceStrategyService.class);
        TestStrategy strat = new TestStrategy();
        strat.setId("strat-1");
        when(strategyService.generateFromIndex(any(), any(), any())).thenReturn(strat);

        new AsyncStrategyGenerator(snapshots, strategyService).submit(claimed("snap-1"));

        verify(snapshots, timeout(2000)).linkGenerated("snap-1", "strat-1");   // id-scoped audit link
        verify(snapshots, never()).failGeneration(any(), any());
    }

    @Test
    void onFailureRecordsTheErrorAndReleasesTheClaim() {
        FeatureIndexSnapshotService snapshots = mock(FeatureIndexSnapshotService.class);
        MultiSourceStrategyService strategyService = mock(MultiSourceStrategyService.class);
        when(strategyService.generateFromIndex(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        new AsyncStrategyGenerator(snapshots, strategyService).submit(claimed("snap-1"));

        verify(snapshots, timeout(2000)).failGeneration("snap-1", "boom");   // releases the claim + records the error
        verify(snapshots, never()).linkGenerated(any(), any());
    }

    @Test
    void aPriorStrategyRoutesThroughTheIncrementalReuseOverload() {
        FeatureIndexSnapshotService snapshots = mock(FeatureIndexSnapshotService.class);
        MultiSourceStrategyService strategyService = mock(MultiSourceStrategyService.class);
        TestStrategy strat = new TestStrategy();
        strat.setId("strat-2");
        when(strategyService.generateFromIndex(any(), any(), any(), any(), any())).thenReturn(strat);   // 5-arg reuse
        TestStrategy prior = new TestStrategy();
        prior.setId("prior-1");

        new AsyncStrategyGenerator(snapshots, strategyService).submit(claimed("snap-2"), null, prior);

        verify(strategyService, timeout(2000)).generateFromIndex(any(), any(), any(), any(), any());   // the reuse overload
        verify(strategyService, never()).generateFromIndex(any(), any(), any());                       // not the 3-arg one
        verify(snapshots, timeout(2000)).linkGenerated("snap-2", "strat-2");
    }
}
