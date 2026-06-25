package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.evidence.SourceSelection;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end (mock mode): a code-only run drives the whole pipeline — CodeEvidenceAdapter → FeatureSeeder →
 * FeatureTagger (mock = seed) → GapDetector → the evidence-first assembler (mock sections cite the allowed id) →
 * persisted TestStrategy. Proves Phases 1–4 work together against the real Spring context and the real DB.
 */
@SpringBootTest
class MultiSourceStrategyServiceIT {

    @Autowired
    private MultiSourceStrategyService service;

    @Test
    void aCodeOnlyRunProducesAndPersistsAMultiSourceStrategy() {
        SourceRef src = SourceRef.code("src/main/java/ca/bnc/PolicyController.java", 30, 35, "return repo.get(appId);");
        ParamModel appId = new ParamModel("appId", ParamLocation.PATH, "string", null, true, null, src);
        ResponseModel ok = new ResponseModel(200, "Policy", List.of("application/json"), "RETURN", src);
        Endpoint endpoint = new Endpoint(HttpMethod.GET, "/policies/{appId}", "getPolicy", List.of(appId), null,
                List.of(ok), List.of(), List.of("application/json"), List.of("ROLE_ADMIN"), src);
        ApiModel model = new ApiModel("code", "ciam-policies", "1", null, List.of(endpoint), Map.of());

        TestStrategy strategy = service.generate("ciam-policies", SourceSelection.ofCode(model), "tester");

        assertThat(strategy.getId()).isNotBlank();
        assertThat(strategy.getSource()).isEqualTo("multi-source");
        assertThat(strategy.getServiceName()).isEqualTo("ciam-policies");
        assertThat(strategy.getLineageId()).isEqualTo(strategy.getId());
        // In mock mode the evidence-first sections ground (the mock cites the allowed endpoint id), so the
        // deliverable carries the per-feature risk register — the whole pipeline produced real structured output.
        assertThat(strategy.getDeliverableJson()).contains("riskRegister").contains("UNDOCUMENTED");
    }
}
