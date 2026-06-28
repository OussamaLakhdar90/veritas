package ca.bnc.qe.veritas.testmgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.TestStrategy;
import ca.bnc.qe.veritas.persistence.TestStrategyRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Branch-maximising unit test for {@link TestStrategyService}: every collaborator is a Mockito mock so each
 * branch of generate (per-section loop, bad-section skip, selfReview carry-forward, schema failure, lineage
 * seeding), reviseSection (JSON vs plain-string value, null deliverable, version/lineage defaults, unknown id),
 * regenerateSection (known/unknown spec, key-present vs node, guidance branch, extract failure), approve, and the
 * markdown derivation are exercised deterministically. Uses a real Jackson ObjectMapper (a value object, not a
 * service seam) so node assembly is exact. Mirrors the existing TestStrategy*Test conventions.
 */
class TestStrategyServiceBranchTest {

    private LlmGateway llm;
    private JsonBlockExtractor jsonExtractor;
    private ResponseSchemaValidator schemaValidator;
    private ModelSelector modelSelector;
    private CostRecorder costRecorder;
    private PromptComposer promptComposer;
    private TestStrategyRepository repository;
    private Preflight preflight;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestStrategyService service;

    @BeforeEach
    void setUp() {
        llm = mock(LlmGateway.class);
        jsonExtractor = mock(JsonBlockExtractor.class);
        schemaValidator = mock(ResponseSchemaValidator.class);
        modelSelector = mock(ModelSelector.class);
        costRecorder = mock(CostRecorder.class);
        promptComposer = mock(PromptComposer.class);
        repository = mock(TestStrategyRepository.class);
        preflight = mock(Preflight.class);

        service = new TestStrategyService(llm, jsonExtractor, schemaValidator, modelSelector, costRecorder,
                promptComposer, repository, objectMapper, preflight, new ca.bnc.qe.veritas.report.CitationSanitizer());
    }

    // ---- shared mock wiring helpers -------------------------------------------------------------------

    /** Make the LLM "available" and route every collaborator so generate completes through the happy loop. */
    private void wireHappyGeneration() {
        when(llm.isAvailable()).thenReturn(true);
        lenient().when(llm.complete(anyString(), anyString())).thenReturn("ignored-raw");
        lenient().when(modelSelector.resolveTier(any(ModelTier.class))).thenReturn("model-x");
        lenient().when(promptComposer.data(anyString(), anyString())).thenAnswer(i -> i.getArgument(0) + "=" + i.getArgument(1));
        lenient().when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("composed-prompt");
        lenient().when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CostResult("model-x", null, 0, 10, 20, 0.25, false));
        // jsonExtractor is a thin passthrough here; the real parse is done by the real ObjectMapper.
        lenient().when(jsonExtractor.extract(anyString())).thenAnswer(i -> i.getArgument(0));
        // Force a deterministic id (AuditableEntity auto-assigns a random UUID at construction) so lineage
        // seeding (lineageId = saved.getId()) is assertable. Both saves of v1 are the same instance.
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> {
            TestStrategy s = i.getArgument(0);
            s.setId("strat-1");
            return s;
        });
    }

    // ---- generate -------------------------------------------------------------------------------------

    @Test
    void generateAssemblesAllSectionsPersistsTwiceAndSeedsLineage() {
        wireHappyGeneration();
        // Drive each generateSection call to return a node carrying exactly its own key. We can't see the key from
        // the prompt easily, so return a node that has ALL keys; service only copies the one matching s.key().
        when(jsonExtractor.extract(anyString())).thenReturn(
                "{\"summary\":\"exec summary\","
                        + "\"scope\":{\"objectives\":[\"obj-a\",\"obj-b\"]},"
                        + "\"riskRegister\":[{\"id\":\"R1\"},{\"id\":\"R2\"}],"
                        + "\"testApproach\":{\"levels\":[\"unit\"]},"
                        + "\"exitCriteria\":[{\"criterion\":\"c1\"}],"
                        + "\"selfReview\":{\"confidence\":73,\"blindSpots\":[\"bs1\"]}}");

        TestStrategy result = service.generate("svc-a", "basis text", "CODE", "owner-1");

        assertThat(result.getServiceName()).isEqualTo("svc-a");
        assertThat(result.getStatus()).isEqualTo("DRAFT");
        assertThat(result.getSource()).isEqualTo("CODE");
        assertThat(result.getOwner()).isEqualTo("owner-1");
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getId()).isEqualTo("strat-1");
        assertThat(result.getLineageId()).isEqualTo("strat-1");   // v1 seeds lineage to its own id
        // 10 section records each at 0.25 -> 2.5 total cost
        assertThat(result.getEstCostUsd()).isEqualTo(2.5);
        assertThat(result.getConfidence()).isEqualTo(73.0);
        assertThat(result.getDeliverableJson())
                .contains("summary").contains("scope").contains("riskRegister")
                .contains("testApproach").contains("exitCriteria").contains("selfReview");
        // markdown derivation hit every populated branch
        assertThat(result.getContentMarkdown())
                .contains("# Test Strategy — svc-a")
                .contains("## Summary").contains("exec summary")
                .contains("## Scope").contains("- obj-a").contains("- obj-b")
                .contains("## Risk register").contains("2 risk(s).")
                .contains("## Test approach")
                .contains("## Exit criteria").contains("1 S.M.A.R.T. criteria.");

        verify(preflight).testStrategy("svc-a", "basis text");
        verify(preflight).requireLlm(llm, "test-strategy");
        verify(schemaValidator).validate(any(), eq("test-strategy.schema.json"));
        verify(repository, times(2)).save(any(TestStrategy.class));   // initial save + lineage re-save
        verify(llm, times(10)).complete(anyString(), anyString());    // one call per section (10 ISTQB sections)
    }

    @Test
    void generateComposesStrategySoFarOnlyForSelfReviewSection() {
        wireHappyGeneration();
        when(jsonExtractor.extract(anyString())).thenReturn("{\"summary\":\"s\",\"selfReview\":{\"confidence\":50}}");

        service.generate("svc-b", "the basis", "CODE", "owner-2");

        // selfReview is the ONLY section that passes the assembled-so-far into the prompt as STRATEGY_SO_FAR.
        verify(promptComposer, times(1)).data(eq("STRATEGY_SO_FAR"), anyString());
        // TEST_BASIS is composed once per section (10 times).
        verify(promptComposer, times(10)).data(eq("TEST_BASIS"), eq("the basis"));
    }

    @Test
    void generateSkipsBadSectionWhenExtractionFailsButStillPersists() {
        wireHappyGeneration();
        // Every section extraction blows up -> generateSection returns null -> no node copied, but generate succeeds.
        when(jsonExtractor.extract(anyString())).thenThrow(new IllegalStateException("no json"));

        TestStrategy result = service.generate("svc-c", "basis", "CODE", "owner-3");

        assertThat(result.getDeliverableJson()).contains("markdown");   // only the rendered markdown is present
        assertThat(result.getConfidence()).isEqualTo(0.0);              // no selfReview -> default 0
        assertThat(result.getContentMarkdown()).isEqualTo("# Test Strategy — svc-c\n\n");
        verify(repository, times(2)).save(any(TestStrategy.class));
    }

    @Test
    void generateSkipsSectionWhenNodeLacksItsOwnKey() {
        wireHappyGeneration();
        // Well-formed JSON, but it never contains any of the section keys -> section.has(key) is false everywhere.
        when(jsonExtractor.extract(anyString())).thenReturn("{\"unrelated\":\"value\"}");

        TestStrategy result = service.generate("svc-d", "basis", "CODE", "owner-4");

        assertThat(result.getDeliverableJson()).doesNotContain("riskRegister");
        assertThat(result.getContentMarkdown()).isEqualTo("# Test Strategy — svc-d\n\n");
    }

    @Test
    void generateWrapsSchemaValidationFailureInIllegalState() {
        wireHappyGeneration();
        when(jsonExtractor.extract(anyString())).thenReturn("{\"summary\":\"s\"}");
        org.mockito.Mockito.doThrow(new IllegalStateException("schema boom"))
                .when(schemaValidator).validate(any(), anyString());

        assertThatThrownBy(() -> service.generate("svc-e", "basis", "CODE", "owner-5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-strategy generation failed")
                .hasMessageContaining("schema boom");
        verify(repository, never()).save(any(TestStrategy.class));
    }

    @Test
    void generateMarkdownOmitsSectionsThatAreBlankOrWrongType() {
        wireHappyGeneration();
        // summary blank, scope a string (not object), riskRegister an object (not array), exitCriteria object.
        when(jsonExtractor.extract(anyString())).thenReturn(
                "{\"summary\":\"   \",\"scope\":\"flat\",\"riskRegister\":{\"x\":1},"
                        + "\"testApproach\":\"flat\",\"exitCriteria\":{\"y\":2}}");

        TestStrategy result = service.generate("svc-f", "basis", "CODE", "owner-6");

        String md = result.getContentMarkdown();
        assertThat(md).startsWith("# Test Strategy — svc-f");
        assertThat(md).doesNotContain("## Summary");        // blank summary skipped
        assertThat(md).doesNotContain("## Scope");          // scope not an object
        assertThat(md).doesNotContain("## Risk register");  // riskRegister not an array
        assertThat(md).doesNotContain("## Test approach");  // testApproach not an object
        assertThat(md).doesNotContain("## Exit criteria");  // exitCriteria not an array
    }

    @Test
    void generatePropagatesPreflightFailureBeforeAnyLlmCall() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("missing service name"))
                .when(preflight).testStrategy(anyString(), anyString());

        assertThatThrownBy(() -> service.generate("", "basis", "CODE", "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing service name");
        verify(llm, never()).complete(anyString(), anyString());
        verify(repository, never()).save(any(TestStrategy.class));
    }

    // ---- reviseSection --------------------------------------------------------------------------------

    @Test
    void reviseSectionUnknownIdThrows() {
        when(repository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reviseSection("nope", "summary", "\"x\"", "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown strategy nope");
    }

    @Test
    void reviseSectionParsesJsonValueAndIncrementsVersionPreservingLineage() {
        TestStrategy current = new TestStrategy();
        current.setId("id-1");
        current.setServiceName("svc");
        current.setDeliverableJson("{\"summary\":\"old\"}");
        current.setSource("CODE");
        current.setOwner("orig-owner");
        current.setVersion(2);
        current.setLineageId("lineage-7");
        current.setConfidence(40.0);
        when(repository.findById("id-1")).thenReturn(Optional.of(current));
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> i.getArgument(0));

        TestStrategy next = service.reviseSection("id-1", "scope",
                "{\"objectives\":[\"o1\"]}", "qa-lead");

        assertThat(next.getVersion()).isEqualTo(3);                 // 2 -> 3
        assertThat(next.getLineageId()).isEqualTo("lineage-7");     // existing lineage kept
        assertThat(next.getRevisedBy()).isEqualTo("qa-lead");
        assertThat(next.getStatus()).isEqualTo("DRAFT");            // edit re-opens the draft
        assertThat(next.getSource()).isEqualTo("CODE");
        assertThat(next.getOwner()).isEqualTo("orig-owner");
        assertThat(next.getDeliverableJson()).contains("\"objectives\"").contains("o1");
        assertThat(next.getContentMarkdown()).contains("# Test Strategy — svc").contains("- o1");
        // selfReview absent -> confidence falls back to the current strategy's confidence (40)
        assertThat(next.getConfidence()).isEqualTo(40.0);
    }

    @Test
    void reviseSectionFallsBackToPlainStringWhenContentIsNotJson() {
        TestStrategy current = new TestStrategy();
        current.setId("id-2");
        current.setServiceName("svc2");
        current.setDeliverableJson(null);     // null deliverable -> createObjectNode branch
        current.setVersion(null);             // null version -> defaults to 1 then +1
        current.setLineageId(null);           // null lineage -> uses current.getId()
        current.setConfidence(null);          // null confidence -> 0 fallback
        when(repository.findById("id-2")).thenReturn(Optional.of(current));
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> i.getArgument(0));

        TestStrategy next = service.reviseSection("id-2", "summary",
                "this is not json at all", "editor");

        assertThat(next.getVersion()).isEqualTo(2);                 // null -> 1 -> 2
        assertThat(next.getLineageId()).isEqualTo("id-2");          // null lineage -> current id
        assertThat(next.getConfidence()).isEqualTo(0.0);            // null confidence -> 0
        // value stored as a JSON text node (quoted string), not parsed structure
        assertThat(next.getDeliverableJson()).contains("\"summary\":\"this is not json at all\"");
        assertThat(next.getContentMarkdown()).contains("## Summary").contains("this is not json at all");
    }

    @Test
    void reviseSectionWrapsBrokenStoredJsonInIllegalState() {
        TestStrategy current = new TestStrategy();
        current.setId("id-3");
        current.setServiceName("svc3");
        current.setDeliverableJson("{not valid json");   // unparseable stored deliverable -> readTree throws
        when(repository.findById("id-3")).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.reviseSection("id-3", "summary", "\"x\"", "actor"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revise-section failed for id-3");
    }

    // ---- regenerateSection ----------------------------------------------------------------------------

    @Test
    void regenerateSectionUnknownIdThrows() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regenerateSection("missing", "summary", null, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown strategy missing");
    }

    @Test
    void regenerateSectionKnownSpecWithGuidanceUnwrapsKeyedNode() {
        TestStrategy current = new TestStrategy();
        current.setId("id-r1");
        current.setServiceName("svc-r");
        current.setDeliverableJson("{\"summary\":\"old\"}");
        current.setVersion(1);
        current.setLineageId("id-r1");
        when(repository.findById("id-r1")).thenReturn(Optional.of(current));
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> i.getArgument(0));
        when(modelSelector.resolveTier(any(ModelTier.class))).thenReturn("model-z");
        when(promptComposer.data(anyString(), anyString())).thenReturn("data-block");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("p");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CostResult.zero("model-z"));
        // node HAS the section key -> service unwraps node.get(sectionKey)
        when(jsonExtractor.extract(anyString())).thenReturn("{\"summary\":\"regenerated\"}");

        TestStrategy next = service.regenerateSection("id-r1", "summary", "be concise", "qa");

        assertThat(next.getVersion()).isEqualTo(2);
        assertThat(next.getLineageId()).isEqualTo("id-r1");
        assertThat(next.getDeliverableJson()).contains("regenerated");
        // guidance present -> it is woven into the contract sent to the prompt composer
        ArgumentCaptor<String> contract = ArgumentCaptor.forClass(String.class);
        verify(promptComposer).compose(anyString(), anyString(), any(), anyString(), contract.capture());
        assertThat(contract.getValue()).contains("User guidance: be concise");
        // known spec for "summary" cites its named ISTQB concept, not the generic default
        assertThat(contract.getValue()).contains("CTAL-TM — Test Scope");
        verify(costRecorder).record(eq("test-strategy"), contains("regenerate:summary"), anyString(),
                anyString(), anyString(), eq("qa"));
    }

    @Test
    void regenerateSectionUnknownKeyUsesDefaultSpecAndRawNodeWhenKeyAbsent() {
        TestStrategy current = new TestStrategy();
        current.setId("id-r2");
        current.setServiceName("svc-r2");
        current.setDeliverableJson(null);   // exercises nz() null -> "" branch
        current.setVersion(5);
        current.setLineageId("lin-x");
        when(repository.findById("id-r2")).thenReturn(Optional.of(current));
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> i.getArgument(0));
        when(modelSelector.resolveTier(any(ModelTier.class))).thenReturn("m");
        when(promptComposer.data(anyString(), anyString())).thenReturn("d");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("p");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CostResult.zero("m"));
        // node does NOT contain the (unknown) key -> service uses the whole node as the value
        when(jsonExtractor.extract(anyString())).thenReturn("{\"freeform\":\"content\"}");

        TestStrategy next = service.regenerateSection("id-r2", "customSection", null, "actor");

        assertThat(next.getVersion()).isEqualTo(6);
        assertThat(next.getLineageId()).isEqualTo("lin-x");
        // whole node stored under the requested (unknown) key
        assertThat(next.getDeliverableJson()).contains("customSection").contains("freeform").contains("content");
        // no guidance -> the contract has no "User guidance:" clause and uses the default ISTQB concept
        ArgumentCaptor<String> contract = ArgumentCaptor.forClass(String.class);
        verify(promptComposer).compose(anyString(), anyString(), any(), anyString(), contract.capture());
        assertThat(contract.getValue()).doesNotContain("User guidance:");
        assertThat(contract.getValue()).contains("Cite ISTQB by NAMED concept (ISTQB)");
    }

    @Test
    void regenerateSectionBlankGuidanceOmitsGuidanceClause() {
        TestStrategy current = new TestStrategy();
        current.setId("id-r3");
        current.setServiceName("svc-r3");
        current.setDeliverableJson("{}");
        current.setVersion(1);
        current.setLineageId("id-r3");
        when(repository.findById("id-r3")).thenReturn(Optional.of(current));
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> i.getArgument(0));
        when(modelSelector.resolveTier(any(ModelTier.class))).thenReturn("m");
        when(promptComposer.data(anyString(), anyString())).thenReturn("d");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("p");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CostResult.zero("m"));
        when(jsonExtractor.extract(anyString())).thenReturn("{\"summary\":\"v\"}");

        service.regenerateSection("id-r3", "summary", "   ", "actor");   // blank guidance

        ArgumentCaptor<String> contract = ArgumentCaptor.forClass(String.class);
        verify(promptComposer).compose(anyString(), anyString(), any(), anyString(), contract.capture());
        assertThat(contract.getValue()).doesNotContain("User guidance:");
    }

    @Test
    void regenerateSectionWrapsExtractionFailureInIllegalState() {
        TestStrategy current = new TestStrategy();
        current.setId("id-r4");
        current.setServiceName("svc-r4");
        current.setDeliverableJson("{}");
        current.setVersion(1);
        current.setLineageId("id-r4");
        when(repository.findById("id-r4")).thenReturn(Optional.of(current));
        when(modelSelector.resolveTier(any(ModelTier.class))).thenReturn("m");
        when(promptComposer.data(anyString(), anyString())).thenReturn("d");
        when(promptComposer.compose(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("p");
        when(llm.complete(anyString(), anyString())).thenReturn("raw");
        when(costRecorder.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CostResult.zero("m"));
        when(jsonExtractor.extract(anyString())).thenThrow(new IllegalStateException("no json block"));

        assertThatThrownBy(() -> service.regenerateSection("id-r4", "summary", null, "actor"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regenerate-section failed for id-r4");
    }

    // ---- approve --------------------------------------------------------------------------------------

    @Test
    void approveLocksStrategyAndStampsActor() {
        TestStrategy current = new TestStrategy();
        current.setId("id-a1");
        current.setStatus("DRAFT");
        when(repository.findById("id-a1")).thenReturn(Optional.of(current));
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> i.getArgument(0));

        TestStrategy approved = service.approve("id-a1", "manager");

        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getRevisedBy()).isEqualTo("manager");
        ArgumentCaptor<TestStrategy> saved = ArgumentCaptor.forClass(TestStrategy.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void approveAlreadyApprovedReStampsButStaysApproved() {
        TestStrategy current = new TestStrategy();
        current.setId("id-a2");
        current.setStatus("APPROVED");
        current.setRevisedBy("first");
        when(repository.findById("id-a2")).thenReturn(Optional.of(current));
        when(repository.save(any(TestStrategy.class))).thenAnswer(i -> i.getArgument(0));

        TestStrategy approved = service.approve("id-a2", "second");

        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getRevisedBy()).isEqualTo("second");   // latest approver stamped
    }

    @Test
    void approveUnknownIdThrows() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve("ghost", "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown strategy ghost");
        verify(repository, never()).save(any(TestStrategy.class));
    }

    // ---- sectionTier (visible-for-testing tier routing) -----------------------------------------------

    @Test
    void sectionTierReturnsConfiguredTierPerKeyAndNullForUnknown() {
        assertThat(TestStrategyService.sectionTier("summary")).isEqualTo(ModelTier.ECONOMY);
        assertThat(TestStrategyService.sectionTier("scope")).isEqualTo(ModelTier.ECONOMY);
        assertThat(TestStrategyService.sectionTier("riskRegister")).isEqualTo(ModelTier.DEEP);
        assertThat(TestStrategyService.sectionTier("testApproach")).isEqualTo(ModelTier.STANDARD);
        assertThat(TestStrategyService.sectionTier("exitCriteria")).isEqualTo(ModelTier.ECONOMY);
        assertThat(TestStrategyService.sectionTier("selfReview")).isEqualTo(ModelTier.STANDARD);
        assertThat(TestStrategyService.sectionTier("does-not-exist")).isNull();
    }

    @Test
    void generateUsesResolvedTierModelForEachSectionCall() {
        wireHappyGeneration();
        when(jsonExtractor.extract(anyString())).thenReturn("{\"summary\":\"s\"}");

        service.generate("svc-tier", "basis", "CODE", "owner");

        // every section resolves its tier through the selector before calling the LLM
        verify(modelSelector, atLeastOnce()).resolveTier(ModelTier.DEEP);       // riskRegister
        verify(modelSelector, atLeastOnce()).resolveTier(ModelTier.ECONOMY);    // summary/scope/exitCriteria
        verify(modelSelector, atLeastOnce()).resolveTier(ModelTier.STANDARD);   // testApproach/selfReview
        verify(llm, atLeastOnce()).complete(eq("composed-prompt"), eq("model-x"));
    }
}
