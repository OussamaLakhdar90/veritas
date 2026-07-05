package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import org.junit.jupiter.api.Test;

/**
 * The reconcile prompt's CODE_API / SPEC_API evidence must be byte-identical across two extractions of the SAME
 * spec, so the SHA-256 prompt key is stable and the persistent cache (#276) hits — otherwise the LLM design/INFO
 * findings are re-generated on every run and appear to "churn" (items swapping in and out). This is the arbiter
 * for the churn root cause: if the prompt drifts, the cache can never converge the INFO tier.
 */
class ReconcilePromptDeterminismTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Schemas deliberately NOT in alphabetical order, to catch source-map ordering that varies across runs.
    private static final String SPEC = """
            openapi: 3.0.0
            info: { title: Bag API, version: 1.0.0 }
            paths:
              /zebras: { get: { responses: { '200': { description: ok } } } }
              /apples: { get: { responses: { '200': { description: ok } } } }
            components:
              schemas:
                Zebra: { type: object, properties: { a: { type: string } } }
                Apple: { type: object, properties: { b: { type: integer } } }
                Mango: { type: object, properties: { c: { type: boolean } } }
            """;

    @Test
    void apiEvidenceIsByteIdenticalAcrossExtractionsOfTheSameSpec() throws Exception {
        ApiModel a = new OpenApiModelExtractor().extract("spec", SPEC).model();
        ApiModel b = new OpenApiModelExtractor().extract("spec", SPEC).model();

        String ja = MAPPER.writeValueAsString(ContractValidationService.apiEvidence(List.of(a)));
        String jb = MAPPER.writeValueAsString(ContractValidationService.apiEvidence(List.of(b)));

        assertThat(ja).isEqualTo(jb);   // identical prompt evidence -> stable SHA-256 cache key -> no churn
    }

    @Test
    void promptJsonSortsMapKeys_soMapOfRandomizedOrderCannotDriftTheCacheKey() throws Exception {
        // The manifest block is a Map.of(...), which iterates in a per-JVM RANDOMIZED order — without sorting, its
        // key order changes every restart, drifts the prompt, and defeats the persistent cache (the real churn
        // cause). promptJson must emit keys in a stable (alphabetical) order.
        String json = ContractValidationService.promptJson(MAPPER,
                java.util.Map.of("resolvedTypes", java.util.List.of(), "parsedEndpoints", 1,
                        "knownGaps", java.util.List.of()));
        assertThat(json.indexOf("knownGaps")).isLessThan(json.indexOf("parsedEndpoints"));
        assertThat(json.indexOf("parsedEndpoints")).isLessThan(json.indexOf("resolvedTypes"));
    }

    @Test
    void apiEvidenceEndpointOrderIsStableRegardlessOfInputOrder() throws Exception {
        // The extractor fills the endpoint list in Files.walk() order (non-deterministic per its JavaDoc). apiEvidence
        // must SORT the list (a LIST is not covered by ORDER_MAP_ENTRIES_BY_KEYS) so the prompt is byte-identical
        // whether the same endpoints arrive in one order or another — the real cross-run churn cause. RED without the sort.
        Endpoint apples = ep("GET", "/apples");
        Endpoint zebras = ep("GET", "/zebras");
        ApiModel m1 = new ApiModel("code", null, null, null, List.of(zebras, apples), Map.of());
        ApiModel m2 = new ApiModel("code", null, null, null, List.of(apples, zebras), Map.of());

        assertThat(MAPPER.writeValueAsString(ContractValidationService.apiEvidence(List.of(m1))))
                .isEqualTo(MAPPER.writeValueAsString(ContractValidationService.apiEvidence(List.of(m2))));
    }

    private static Endpoint ep(String method, String path) {
        return new Endpoint(HttpMethod.valueOf(method), path, path, List.of(), null, List.of(), null, null, List.of(),
                SourceRef.code("X.java", 1, 1, null));
    }
}
