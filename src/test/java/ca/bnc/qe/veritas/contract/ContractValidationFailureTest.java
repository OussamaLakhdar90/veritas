package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import org.junit.jupiter.api.Test;

/**
 * A FAILED scan must preserve the stage it actually failed on (so the UI can show where, instead of always
 * blaming the AI step) and must NOT keep a stale live sub-step detail. Guards the review fix for #2/#3.
 */
class ContractValidationFailureTest {

    @Test
    void failedScanPreservesFailingStageAndClearsStageDetail() {
        var javaExtractor = mock(ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor.class);
        var scanRepo = mock(ScanRepository.class);
        when(javaExtractor.extract(any())).thenThrow(new RuntimeException("boom in extract"));
        when(scanRepo.save(any(Scan.class))).thenAnswer(inv -> inv.getArgument(0));

        ContractValidationService svc = new ContractValidationService(
                javaExtractor,
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
                mock(ca.bnc.qe.veritas.report.TranslationService.class));

        Scan scan = new Scan();
        scan.setStage(ScanStages.QUEUED);
        scan.setStageDetail("Generating the corrected spec…");   // simulate a stale live detail from a prior stage
        ValidationRequest req = new ValidationRequest("svc", "APP", "repo", "main",
                Path.of("nonexistent"), List.of(), true, "owner");

        svc.runInto(scan, req);

        assertThat(scan.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(scan.getStage()).isEqualTo(ScanStages.FAILED);
        // runInto sets EXTRACTING then calls extract(), which throws — so EXTRACTING is the real failing stage.
        assertThat(scan.getFailedStage()).isEqualTo(ScanStages.EXTRACTING);
        assertThat(scan.getStageDetail()).isNull();
        assertThat(scan.getErrorMessage()).contains("boom in extract");
    }
}
