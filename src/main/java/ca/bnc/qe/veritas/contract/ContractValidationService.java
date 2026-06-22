package ca.bnc.qe.veritas.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.BillingMode;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import ca.bnc.qe.veritas.llm.JsonBlockExtractor;
import ca.bnc.qe.veritas.llm.LlmGateway;
import ca.bnc.qe.veritas.llm.PromptComposer;
import ca.bnc.qe.veritas.llm.ResponseSchemaValidator;
import ca.bnc.qe.veritas.persistence.FindingRecord;
import ca.bnc.qe.veritas.persistence.FindingRecordRepository;
import ca.bnc.qe.veritas.persistence.RunStatus;
import ca.bnc.qe.veritas.persistence.Scan;
import ca.bnc.qe.veritas.persistence.ScanRepository;
import ca.bnc.qe.veritas.preflight.Preflight;
import ca.bnc.qe.veritas.report.ContractReportRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Pillar A: deterministic extraction + diff (engine) → optional LLM reconcile (Copilot, or
 * mock locally) → persisted findings + a management report + corrected YAML. The deterministic work is the
 * bulk; the LLM is one batched call (cost-tracked).
 */
@Service
@Slf4j
public class ContractValidationService {

    private final JavaSpringExtractor javaSpringExtractor;
    private final OpenApiModelExtractor openApiModelExtractor;
    private final CorrectedSpecBuilder correctedSpecBuilder;
    private final DiffEngine diffEngine;
    private final LlmGateway llm;
    private final JsonBlockExtractor jsonExtractor;
    private final ResponseSchemaValidator schemaValidator;
    private final ModelSelector modelSelector;
    private final CostRecorder costRecorder;
    private final PromptComposer promptComposer;
    private final ContractReportRenderer reportRenderer;
    private final ScanRepository scanRepository;
    private final FindingRecordRepository findingRepository;
    private final ObjectMapper objectMapper;
    private final Preflight preflight;
    private final ScanPersistence scanPersistence;
    private final ca.bnc.qe.veritas.report.TranslationService translationService;

    /** Per-batch token budget for the reconcile findings list (chunk-and-merge for large scans). 0 = never batch. */
    @Value("${veritas.llm.batch-input-tokens:24000}")
    private int batchInputTokens;

    @org.springframework.beans.factory.annotation.Value("${veritas.report.bilingual:true}")
    private boolean bilingualReport;

    public ContractValidationService(JavaSpringExtractor javaSpringExtractor,
                                     OpenApiModelExtractor openApiModelExtractor,
                                     CorrectedSpecBuilder correctedSpecBuilder,
                                     DiffEngine diffEngine,
                                     LlmGateway llm,
                                     JsonBlockExtractor jsonExtractor,
                                     ResponseSchemaValidator schemaValidator,
                                     ModelSelector modelSelector,
                                     CostRecorder costRecorder,
                                     PromptComposer promptComposer,
                                     ContractReportRenderer reportRenderer,
                                     ScanRepository scanRepository,
                                     FindingRecordRepository findingRepository,
                                     ObjectMapper objectMapper,
                                     Preflight preflight,
                                     ScanPersistence scanPersistence,
                                     ca.bnc.qe.veritas.report.TranslationService translationService) {
        this.javaSpringExtractor = javaSpringExtractor;
        this.openApiModelExtractor = openApiModelExtractor;
        this.correctedSpecBuilder = correctedSpecBuilder;
        this.diffEngine = diffEngine;
        this.llm = llm;
        this.jsonExtractor = jsonExtractor;
        this.schemaValidator = schemaValidator;
        this.modelSelector = modelSelector;
        this.costRecorder = costRecorder;
        this.promptComposer = promptComposer;
        this.reportRenderer = reportRenderer;
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.objectMapper = objectMapper;
        this.preflight = preflight;
        this.scanPersistence = scanPersistence;
        this.translationService = translationService;
    }

    public ValidationResult validate(ValidationRequest req) {
        preflight.validateContract(req);   // fail fast with clear remediation if inputs/config are missing
        Scan scan = new Scan();
        scan.setServiceName(req.serviceName());
        scan.setAppId(req.appId());
        scan.setRepoSlug(req.repoSlug());
        scan.setGitRef(req.gitRef());
        scan.setOwner(req.owner());
        scan.setStatus(RunStatus.RUNNING);
        scan.setStartedAt(Instant.now());
        scan.setSpecSources(String.join(",", req.specs().stream().map(SpecInput::id).toList()));
        scan = scanRepository.save(scan);

        List<Finding> findings = new ArrayList<>();
        Map<String, JsonNode> enrich = new HashMap<>();
        String correctedYamlPath = null;
        try {
            ApiModel code = javaSpringExtractor.extract(req.repoPath());
            List<ApiModel> specModels = new ArrayList<>();
            for (SpecInput s : req.specs()) {
                SpecParse parse = openApiModelExtractor.extract(s.id(), s.content());
                findings.addAll(diffEngine.l1FromMessages(s.id(), parse.messages()));
                if (parse.parsed()) {
                    specModels.add(parse.model());
                    findings.addAll(diffEngine.diffCodeVsSpec(code, parse.model()));
                }
            }
            // spec-vs-spec drift (e.g. repo YAML vs Confluence YAML)
            for (int i = 0; i < specModels.size(); i++) {
                for (int j = i + 1; j < specModels.size(); j++) {
                    findings.addAll(diffEngine.diffSpecVsSpec(specModels.get(i), specModels.get(j)));
                }
            }

            String llmCorrected = null;
            if (req.llmEnabled() && !findings.isEmpty() && llm.isAvailable()) {
                ReconcileResult rr = reconcile(code, specModels, findings, req.owner(), scan.getId());
                enrich.putAll(rr.enrich());
                scan.setTotalPremiumRequests(rr.cost().premiumRequests());
                scan.setTotalEstCostUsd(rr.cost().estCostUsd());
                llmCorrected = rr.correctedYaml();
                findings.addAll(rr.llmFindings());   // L5/L6 design + test-basis findings (LLM judgment)
                scan.setConfidence(rr.confidence());
                scan.setBlindSpots(rr.blindSpots());
            }

            // Always surface deterministic static-analysis blind spots (unparsed files, unresolved DTOs),
            // merged with any LLM self-review blind spots — never silently dropped.
            int coverageGaps = code.blindSpots() == null ? 0 : code.blindSpots().size();
            scan.setCoverageGaps(coverageGaps);   // deterministic per-scan gaps drive the Coverage KPI
            if (coverageGaps > 0) {
                String existing = scan.getBlindSpots();
                String extractor = String.join(" ", code.blindSpots());
                scan.setBlindSpots(existing == null || existing.isBlank() ? extractor : existing + " " + extractor);
            }

            // Corrected YAML: prefer the LLM-reconciled spec IF it round-trips; else the deterministic
            // code-wins spec; never write a spec that fails to re-parse.
            String primarySpecYaml = req.specs().isEmpty() ? null : req.specs().get(0).content();
            String corrected = chooseCorrectedYaml(llmCorrected, code, req.serviceName(), primarySpecYaml);
            if (corrected != null) {
                correctedYamlPath = writeOut("openapi.corrected-" + scan.getId() + ".yaml", corrected);
            }

            // Populate the "current YAML" fragment per finding (deterministic) so the report can show it.
            findings = enrichWithSpecFragments(findings, req.specs());

            // Reference each finding to the CORRECT standard for its type (OpenAPI / HTTP / JSON Schema),
            // deterministically — contract fidelity is API governance, not ISTQB testing (ISTQB fits only L6).
            findings = findings.stream()
                    .map(f -> f.toBuilder().citation(ca.bnc.qe.veritas.finding.StandardsReference.forType(f.getType())).build())
                    .toList();

            scan.setTotalFindings(findings.size());

            // Contract Fidelity Score + trend vs the previous scan of this service (deterministic, management KPI).
            final Scan current = scan;
            current.setFidelityScore(ca.bnc.qe.veritas.report.FidelityScore.of(findings));
            scanRepository.findAllByOrderByStartedAtDesc().stream()
                    .filter(s -> req.serviceName() != null && req.serviceName().equals(s.getServiceName()))
                    .filter(s -> s.getFidelityScore() != null && !s.getId().equals(current.getId()))
                    .findFirst()
                    .ifPresent(prev -> current.setPreviousFidelityScore(prev.getFidelityScore()));

            // Report generation is deterministic; the ONLY LLM use here is translating the dynamic strings to
            // French on the cheapest tier (TranslationService). Non-fatal — falls back to English.
            java.util.Map<String, String> fr = java.util.Map.of();
            if (bilingualReport) {
                java.util.LinkedHashSet<String> toTranslate = new java.util.LinkedHashSet<>();
                for (Finding f : findings) {
                    if (f.getSummary() != null) {
                        toTranslate.add(f.getSummary());
                    }
                    if (f.getExplanation() != null) {
                        toTranslate.add(f.getExplanation());
                    }
                    if (f.getProposedFix() != null) {
                        toTranslate.add(f.getProposedFix());
                    }
                }
                if (scan.getBlindSpots() != null) {
                    toTranslate.add(scan.getBlindSpots());
                }
                if (!toTranslate.isEmpty()) {
                    fr = translationService.toFrench(toTranslate, req.owner());
                }
            }

            String html = reportRenderer.renderHtml(scan, findings, fr);
            String reportPath = writeOut("contract-report-" + scan.getId() + ".html", html);
            String reportPdfPath = null;
            try {
                reportPdfPath = writeOutBytes("contract-report-" + scan.getId() + ".pdf",
                        reportRenderer.renderPdf(scan, findings, fr));
            } catch (Exception ex) {
                log.warn("PDF report skipped: {}", ex.getMessage());
            }
            scan.setStatus(RunStatus.COMPLETED);
            scan.setFinishedAt(Instant.now());
            // Atomic: the findings and the COMPLETED scan are written together (one transaction).
            scanPersistence.complete(scan, findings, enrich);

            return new ValidationResult(scan.getId(), scan.getStatus().name(), findings.size(),
                    bySeverity(findings), reportPath, reportPdfPath, correctedYamlPath, scan.getTotalEstCostUsd());
        } catch (Exception ex) {
            log.error("validate-contract failed for {}", req.serviceName(), ex);
            scan.setStatus(RunStatus.FAILED);
            scan.setErrorMessage(ex.getMessage());
            scan.setFinishedAt(Instant.now());
            scanRepository.save(scan);
            return new ValidationResult(scan.getId(), "FAILED", findings.size(),
                    bySeverity(findings), null, null, null, scan.getTotalEstCostUsd());
        }
    }

    private record ReconcileResult(String correctedYaml, Map<String, JsonNode> enrich, CostResult cost,
                                   List<Finding> llmFindings, Double confidence, String blindSpots) {}

    private ReconcileResult reconcile(ApiModel code, List<ApiModel> specs, List<Finding> findings,
                                      String owner, String scanId) throws Exception {
        List<Map<String, String>> brief = new ArrayList<>();
        for (Finding f : findings) {
            brief.add(Map.of("findingId", f.getFindingId(), "type", f.getType().name(), "summary", nz(f.getSummary())));
        }
        String codeEps = code.endpoints().stream().map(Endpoint::signature).toList().toString();
        List<String> specEps = new ArrayList<>();
        specs.forEach(s -> s.endpoints().forEach(e -> specEps.add(e.signature())));

        String outputContract = "Code is the source of truth for behaviour. For each finding add a short "
                + "explanation and a proposed fix; then produce a reconciled corrected OpenAPI YAML (code wins on "
                + "behaviour). ALSO add L5 (design-quality) and L6 (test-basis adequacy) judgements in "
                + "designFindings — e.g. a spec with no examples/constraints/error responses is a weak test basis. "
                + "Do NOT add citations — Veritas attaches the governing standard (OpenAPI / HTTP / JSON Schema) "
                + "deterministically. Reply with exactly one fenced ```json block as the LAST thing, matching: "
                + "{\"correctedYaml\": string, \"findings\": [{\"findingId\": string, \"explanation\": string, "
                + "\"proposedFix\": string}], \"designFindings\": [{\"layer\": \"L5\"|\"L6\", \"severity\": string, "
                + "\"endpoint\": string, \"summary\": string, \"explanation\": string}]}. No prose after the json.";

        // Chunk-and-merge: a large finding set would otherwise have its middle elided by the prompt cap.
        // Batch the findings (deterministic) and merge the per-finding enrichment; the corrected YAML and design
        // findings come from the first batch (it carries the full endpoint context). Small scans → one call.
        List<List<Map<String, String>>> batches = partitionByTokens(brief, batchInputTokens);
        String model = modelSelector.resolveTier(ModelTier.STANDARD);

        Map<String, JsonNode> enrich = new HashMap<>();
        List<Finding> design = new ArrayList<>();
        Set<String> designIds = new HashSet<>();
        String correctedYaml = null;
        Double confidence = null;
        List<String> blind = new ArrayList<>();
        double premium = 0;
        double costUsd = 0;
        long tokIn = 0;
        long tokOut = 0;
        BillingMode mode = null;

        for (List<Map<String, String>> batch : batches) {
            String batchJson = objectMapper.writeValueAsString(batch);
            String inputs = promptComposer.data("DETERMINISTIC_FINDINGS", batchJson)
                    + promptComposer.data("CODE_ENDPOINTS", codeEps)
                    + promptComposer.data("SPEC_ENDPOINTS", specEps.toString());
            String prompt = promptComposer.compose("[CONTRACT-RECONCILE]", "validate-service-contract.prompt.md",
                    Set.of("1", "6", "12"), inputs, outputContract, modelSelector.promptTokenCap(model));
            String raw = llm.complete(prompt, model);
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "contract-reconcile.schema.json");
            CostResult c = costRecorder.record("validate-contract", "reconcile", model, prompt, raw, owner, scanId);
            premium += c.premiumRequests();
            costUsd += c.estCostUsd();
            tokIn += c.estTokensIn();
            tokOut += c.estTokensOut();
            mode = c.billingMode();

            if (node.get("findings") != null) {
                for (JsonNode fn : node.get("findings")) {
                    if (fn.hasNonNull("findingId")) {
                        enrich.put(fn.get("findingId").asText(), fn);
                    }
                }
            }
            for (Finding df : parseDesignFindings(node)) {
                if (designIds.add(df.getFindingId())) {
                    design.add(df);
                }
            }
            if (correctedYaml == null && node.hasNonNull("correctedYaml")) {
                correctedYaml = node.get("correctedYaml").asText();
            }
            JsonNode sr = node.path("selfReview");
            if (sr.hasNonNull("confidence")) {
                double cf = sr.path("confidence").asDouble();
                confidence = confidence == null ? cf : Math.min(confidence, cf);
            }
            sr.path("blindSpots").forEach(n -> {
                if (!blind.contains(n.asText())) {
                    blind.add(n.asText());
                }
            });
        }
        if (batches.size() > 1) {
            blind.add("Reconcile ran in " + batches.size() + " batches over " + findings.size()
                    + " findings to fit the model context; per-finding enrichment is merged across batches.");
        }
        CostResult cost = new CostResult(model, mode == null ? BillingMode.PER_REQUEST : mode, premium, tokIn, tokOut,
                Math.round(costUsd * 10_000.0) / 10_000.0);
        String blindSpots = blind.isEmpty() ? null : String.join("; ", blind);
        return new ReconcileResult(correctedYaml, enrich, cost, design, confidence, blindSpots);
    }

    /** Split findings into batches whose serialized JSON stays within a per-call token budget (≥1 batch). */
    private List<List<Map<String, String>>> partitionByTokens(List<Map<String, String>> items, int budgetTokens)
            throws Exception {
        List<List<Map<String, String>>> out = new ArrayList<>();
        if (items.isEmpty() || budgetTokens <= 0) {
            out.add(new ArrayList<>(items));
            return out;
        }
        List<Map<String, String>> current = new ArrayList<>();
        int curTokens = 0;
        for (Map<String, String> it : items) {
            int t = PromptComposer.estimateTokens(objectMapper.writeValueAsString(it));
            if (!current.isEmpty() && curTokens + t > budgetTokens) {
                out.add(current);
                current = new ArrayList<>();
                curTokens = 0;
            }
            current.add(it);
            curTokens += t;
        }
        out.add(current);
        return out;
    }

    /** L5/L6 LLM judgement findings (design quality + test-basis adequacy) from the reconcile reply. */
    private List<Finding> parseDesignFindings(JsonNode node) {
        List<Finding> out = new ArrayList<>();
        for (JsonNode d : node.path("designFindings")) {
            String layer = d.path("layer").asText("L5");
            boolean l6 = "L6".equalsIgnoreCase(layer);
            FindingType type = l6 ? FindingType.TEST_BASIS_GAP : FindingType.DESIGN_QUALITY;
            String summary = d.path("summary").asText("");
            String endpoint = d.hasNonNull("endpoint") ? d.path("endpoint").asText() : null;
            out.add(Finding.builder()
                    .findingId(Integer.toHexString(java.util.Objects.hash(type, summary, endpoint)))
                    .type(type)
                    .layer(l6 ? Layer.L6 : Layer.L5)
                    .severity(parseSeverity(d.path("severity").asText(null)))
                    .confidence(Confidence.MEDIUM)
                    .origin("LLM")
                    .endpoint(endpoint)
                    .specSource("code-vs-spec")
                    .summary(summary)
                    .explanation(d.hasNonNull("explanation") ? d.path("explanation").asText() : null)
                    .citation(d.hasNonNull("citation") ? d.path("citation").asText() : null)
                    .build());
        }
        return out;
    }

    private Severity parseSeverity(String s) {
        try {
            return s == null ? Severity.MAJOR : Severity.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Severity.MAJOR;
        }
    }

    /** Prefer the LLM-reconciled corrected YAML when it re-parses; otherwise the deterministic code-wins spec. */
    private String chooseCorrectedYaml(String llmCorrected, ApiModel code, String title, String originalSpecYaml) {
        if (llmCorrected != null && roundTrips(llmCorrected)) {
            return llmCorrected;
        }
        String deterministic = correctedSpecBuilder.build(code, title, originalSpecYaml);
        return roundTrips(deterministic) ? deterministic : null;
    }

    private boolean roundTrips(String yaml) {
        try {
            return yaml != null && openApiModelExtractor.extract("corrected-check", yaml).parsed();
        } catch (Exception e) {
            return false;
        }
    }

    /** Attach the matching "current YAML" fragment to each endpoint finding (deterministic, for the report). */
    private List<Finding> enrichWithSpecFragments(List<Finding> findings, List<SpecInput> specs) {
        List<Finding> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            if (f.getEndpoint() == null || f.getCurrentYamlFragment() != null) {
                out.add(f);
                continue;
            }
            String fragment = null;
            for (SpecInput s : specs) {
                fragment = SpecFragmentExtractor.extract(s.content(), f.getEndpoint());
                if (fragment != null) {
                    break;
                }
            }
            out.add(fragment == null ? f : f.toBuilder().currentYamlFragment(fragment).build());
        }
        return out;
    }

    private Map<String, Long> bySeverity(List<Finding> findings) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String sev : List.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")) {
            long n = findings.stream().filter(f -> f.getSeverity().name().equals(sev)).count();
            if (n > 0) {
                counts.put(sev, n);
            }
        }
        return counts;
    }

    private String writeOut(String fileName, String content) throws Exception {
        Path outDir = Path.of("out");
        Files.createDirectories(outDir);
        Path file = outDir.resolve(fileName);
        Files.writeString(file, content);
        return file.toAbsolutePath().toString();
    }

    private String writeOutBytes(String fileName, byte[] content) throws Exception {
        Path outDir = Path.of("out");
        Files.createDirectories(outDir);
        Path file = outDir.resolve(fileName);
        Files.write(file, content);
        return file.toAbsolutePath().toString();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
