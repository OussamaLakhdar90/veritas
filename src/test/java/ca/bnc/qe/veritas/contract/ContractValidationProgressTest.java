package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bnc.qe.veritas.llm.LlmCallContext;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.Test;

/**
 * The live AI-generating progress sink: it writes the reply length into the scan's stageDetail as tokens stream,
 * but THROTTLES a burst of tiny deltas into one DB write so streaming never thrashes the optimistic lock.
 */
class ContractValidationProgressTest {

    private ContractValidationService service(ScanRepository scanRepo) {
        return new ContractValidationService(
                mock(ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor.class),
                mock(ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor.class),
                mock(ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder.class),
                mock(ca.bnc.qe.veritas.engine.diff.DiffEngine.class),
                mock(ca.bnc.qe.veritas.llm.LlmGateway.class),
                mock(ca.bnc.qe.veritas.llm.JsonBlockExtractor.class),
                mock(ca.bnc.qe.veritas.llm.ResponseSchemaValidator.class),
                mock(ca.bnc.qe.veritas.cost.ModelSelector.class),
                mock(ca.bnc.qe.veritas.cost.CostRecorder.class),
                mock(ca.bnc.qe.veritas.llm.PromptComposer.class),
                mock(ca.bnc.qe.veritas.report.ContractReportRenderer.class),
                scanRepo,
                mock(ca.bnc.qe.veritas.persistence.FindingRecordRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(ca.bnc.qe.veritas.preflight.Preflight.class),
                mock(ScanPersistence.class),
                mock(ca.bnc.qe.veritas.report.TranslationService.class),
                new LlmCallContext());
    }

    @Test
    void progressSinkThrottlesBurstsAndWritesTheLiveDetail() {
        ScanRepository scanRepo = mock(ScanRepository.class);
        when(scanRepo.save(any(Scan.class))).thenAnswer(inv -> inv.getArgument(0));
        ContractValidationService svc = service(scanRepo);

        Scan scan = new Scan();
        LlmCallContext.ProgressSink sink = svc.reconcileProgressSink(scan, 1, 1);

        sink.onProgress(500);    // first write — the time gate is open (no prior write)
        sink.onProgress(900);    // SUPPRESSED — only +400 chars and <750ms since the last write
        sink.onProgress(1600);   // write — +1100 chars from the last write exceeds the char step

        verify(scanRepo, times(2)).save(any(Scan.class));   // the middle burst collapsed → 2 writes, not 3
        assertThat(scan.getStageDetail()).contains("AI generating").contains("1600");
    }

    @Test
    void progressSinkForABatchedRunNamesTheBatch() {
        ScanRepository scanRepo = mock(ScanRepository.class);
        when(scanRepo.save(any(Scan.class))).thenAnswer(inv -> inv.getArgument(0));
        ContractValidationService svc = service(scanRepo);

        Scan scan = new Scan();
        svc.reconcileProgressSink(scan, 2, 3).onProgress(1200);

        assertThat(scan.getStageDetail()).contains("batch 2 of 3").contains("AI generating");
    }
}
