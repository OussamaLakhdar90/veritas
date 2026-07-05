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
import java.util.regex.Pattern;
import ca.bnc.qe.veritas.cost.BillingMode;
import ca.bnc.qe.veritas.cost.CostRecorder;
import ca.bnc.qe.veritas.cost.CostResult;
import ca.bnc.qe.veritas.cost.ModelSelector;
import ca.bnc.qe.veritas.cost.ModelTier;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.engine.openapi.CorrectedSpecBuilder;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.engine.openapi.SpecPresence;
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

    /** A dropped Copilot SSE stream can't resume — retry the whole reconcile up to this many times before degrading. */
    private static final int RECONCILE_MAX_ATTEMPTS = 3;

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
    private final ca.bnc.qe.veritas.llm.LlmCallContext callContext;

    /** Throttle for the live AI-generating progress detail — write at most every PROGRESS_MS_STEP ms / PROGRESS_CHAR_STEP chars. */
    private static final long PROGRESS_MS_STEP = 750L;
    private static final long PROGRESS_CHAR_STEP = 1000L;

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
                                     ca.bnc.qe.veritas.report.TranslationService translationService,
                                     ca.bnc.qe.veritas.llm.LlmCallContext callContext) {
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
        this.callContext = callContext;
    }

    /** Synchronous entry (CLI/tests): create the scan row, then run the pipeline on the current thread. */
    public ValidationResult validate(ValidationRequest req) {
        return runInto(createScanRow(req), req);
    }

    /** Create the RUNNING scan row up front so an async caller can return its id immediately and track stages. */
    public Scan createScanRow(ValidationRequest req) {
        preflight.validateContract(req);   // fail fast with clear remediation if inputs/config are missing
        Scan scan = new Scan();
        scan.setServiceName(req.serviceName());
        scan.setAppId(req.appId());
        scan.setRepoSlug(req.repoSlug());
        scan.setGitRef(req.gitRef());
        scan.setOwner(req.owner());
        scan.setStatus(RunStatus.RUNNING);
        scan.setStage(ScanStages.QUEUED);
        scan.setStartedAt(Instant.now());
        scan.setSpecSources(String.join(",", req.specs().stream().map(SpecInput::id).toList()));
        return scanRepository.save(scan);
    }

    /** Updates the live progress stage on the scan — drives the dashboard stepper AND logs the advance. */
    private void stage(Scan scan, String stage) {
        scan.setStage(stage);
        scan.setStageDetail(null);   // sub-step detail is per-stage; clear it on every transition so it never goes stale
        persist(scan);
        log.info("Scan {} [{}] → {} — {}", scan.getId(), scan.getServiceName(), stage, ScanStages.describe(stage));
    }

    /** Update the live sub-step detail of the current stage (persisted, so the polling UI shows it in real time).
     *  Returns false if the save was rejected because the scan was finalized externally — callers can stop early. */
    private boolean detail(Scan scan, String stageDetail) {
        scan.setStageDetail(stageDetail);
        return persist(scan);
    }

    /**
     * Persist a live scan-progress update, keeping the in-memory @Version in step so the next save on the same
     * instance doesn't self-conflict. If the scan was finalized externally (e.g. the stale-timeout reconciler marked
     * it FAILED), the optimistic-lock conflict is swallowed (we never resurrect it; the authoritative write wins) and
     * {@code false} is returned so the caller can stop doing expensive work (e.g. more LLM calls) on a dead scan.
     */
    private boolean persist(Scan scan) {
        try {
            Scan saved = scanRepository.save(scan);
            scan.setVersion(saved.getVersion());
            return true;
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            log.warn("Scan {} was finalized externally (likely the stale-timeout reconciler) — skipping progress update",
                    scan.getId());
            return false;
        }
    }

    /** The live "AI generating…" detail for the reconcile step (well within {@code Scan.stageDetail}'s column width). */
    private static String aiDetail(int batchNo, int totalBatches, long chars) {
        return totalBatches > 1
                ? "Reviewing findings — batch " + batchNo + " of " + totalBatches + " — AI generating… ~" + chars + " chars"
                : "Generating the corrected spec — AI generating… ~" + chars + " chars";
    }

    /**
     * A throttled progress sink that updates the scan's live AI-generating detail as the streamed reply grows. It
     * writes at most once per {@code PROGRESS_MS_STEP}/{@code PROGRESS_CHAR_STEP} (a burst of tiny deltas collapses to
     * one DB write), routing through the version-synced {@link #detail} so it never thrashes the optimistic lock.
     * Package-private for direct testing of the throttle. */
    ca.bnc.qe.veritas.llm.LlmCallContext.ProgressSink reconcileProgressSink(Scan scan, int batchNo, int totalBatches) {
        final long[] lastWriteMs = {0L};
        final long[] lastChars = {0L};
        return chars -> {
            long now = System.currentTimeMillis();
            if (chars - lastChars[0] < PROGRESS_CHAR_STEP && now - lastWriteMs[0] < PROGRESS_MS_STEP) {
                return;
            }
            lastChars[0] = chars;
            lastWriteMs[0] = now;
            detail(scan, aiDetail(batchNo, totalBatches, chars));
        };
    }

    /** Run extract → diff → reconcile → report into a pre-created scan row, updating its stage as it goes. */
    public ValidationResult runInto(Scan scan, ValidationRequest req) {
        List<Finding> findings = new ArrayList<>();
        Map<String, JsonNode> enrich = new HashMap<>();
        Map<String, String> disputes = new HashMap<>();   // findingId -> AI dispute reason (from reconcile)
        String correctedYamlPath = null;
        try {
            stage(scan, ScanStages.EXTRACTING);
            ApiModel code = javaSpringExtractor.extract(req.repoPath());
            stage(scan, ScanStages.DIFFING);
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
                stage(scan, ScanStages.RECONCILING);
                long t0 = System.currentTimeMillis();
                log.info("Scan {} [{}] asking Copilot to reconcile {} finding(s) — this is the long step, please wait…",
                        scan.getId(), scan.getServiceName(), findings.size());
                // Deterministic presence facts (from a fully-resolved parse) fact-check the LLM's L5/L6 absence
                // judgements so it can't claim "no examples/properties/error responses" the spec actually has.
                SpecPresence presence = SpecPresence.empty();
                for (SpecInput s : req.specs()) {
                    presence = presence.merge(openApiModelExtractor.presenceOf(s.content()));
                }
                // A dropped Copilot SSE stream can't be resumed (the partial response is unusable) — so retry the WHOLE
                // reconcile a few times, telling the user in the progress bar each time, before giving up. Only after
                // every attempt fails do we degrade to the deterministic diff-only report.
                RuntimeException lastError = null;
                for (int attempt = 1; attempt <= RECONCILE_MAX_ATTEMPTS; attempt++) {
                    try {
                        ReconcileResult rr = reconcile(code, specModels, findings, req.owner(), scan, presence,
                                req.thoroughness().tier());
                        log.info("Scan {} [{}] AI reconcile finished in {}s (attempt {}/{})", scan.getId(),
                                scan.getServiceName(), (System.currentTimeMillis() - t0) / 1000, attempt,
                                RECONCILE_MAX_ATTEMPTS);
                        enrich.putAll(rr.enrich());
                        disputes.putAll(rr.disputes());
                        scan.setTotalPremiumRequests(rr.cost().premiumRequests());
                        scan.setTotalEstCostUsd(rr.cost().estCostUsd());
                        llmCorrected = rr.correctedYaml();
                        findings.addAll(rr.llmFindings());   // L5/L6 design + test-basis findings (LLM judgment)
                        scan.setConfidence(rr.confidence());
                        scan.setBlindSpots(rr.blindSpots());
                        lastError = null;
                        break;
                    } catch (RuntimeException e) {
                        lastError = e;
                        log.warn("Scan {} [{}] AI reconcile attempt {}/{} failed: {}", scan.getId(),
                                scan.getServiceName(), attempt, RECONCILE_MAX_ATTEMPTS, e.getMessage());
                        if (attempt < RECONCILE_MAX_ATTEMPTS) {
                            // Surface the retry live in the progress bar — the connection dropped, we're re-asking Copilot.
                            detail(scan, "AI review connection dropped — retrying (" + (attempt + 1) + "/"
                                    + RECONCILE_MAX_ATTEMPTS + ")…");
                        }
                    }
                }
                if (lastError != null) {
                    // Every attempt failed — degrade to diff-only and record WHY, so the run is honest, not lost.
                    log.warn("Scan {} [{}] AI reconcile failed after {} attempts — degrading to diff-only: {}",
                            scan.getId(), scan.getServiceName(), RECONCILE_MAX_ATTEMPTS, lastError.getMessage());
                    String note = "AI review could not run this time after " + RECONCILE_MAX_ATTEMPTS + " attempts ("
                            + shortReason(lastError) + ") — the findings below are from static analysis only; "
                            + "re-run to include the AI review.";
                    String existing = scan.getBlindSpots();
                    scan.setBlindSpots(existing == null || existing.isBlank() ? note : existing + " " + note);
                }
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

            stage(scan, ScanStages.REPORTING);
            // Corrected YAML: prefer the LLM-reconciled spec IF it round-trips; else the deterministic
            // code-wins spec; never write a spec that fails to re-parse.
            String primarySpecYaml = req.specs().isEmpty() ? null : req.specs().get(0).content();
            String corrected = chooseCorrectedYaml(llmCorrected, code, req.serviceName(), primarySpecYaml);
            if (corrected != null) {
                correctedYamlPath = writeOut("openapi.corrected-" + scan.getId() + ".yaml", corrected);
            }

            // Coverage honesty: when extraction was complete, strip false "source not supplied" disclaimers so the
            // report's manual-review items can't contradict its §7 coverage verdict.
            findings = ca.bnc.qe.veritas.report.CoverageReconciler.stripFalseSourceDisclaimers(findings, code);
            // Collapse duplicate findings about the same endpoint+issue (deterministic + LLM) before scoring/render.
            findings = dedupCrossList(findings);
            // Graft the LLM per-finding enrichment (explanation / proposed fix) onto the in-memory findings BEFORE the
            // root-cause collapse: the LLM saw (and keyed enrich/disputes by) each pre-collapse finding's id, and a
            // spec-keyed multi-endpoint collapse re-fingerprints the survivor — so grafting after the collapse would
            // always miss for the shared-schema case (explanation/proposedFix dropped, a disputed FP never marked). The
            // surviving first occurrence carries these fields through the toBuilder() collapse.
            findings = graftEnrichment(findings, enrich, disputes);
            // Then collapse the SAME root cause across endpoints — a shared DTO field flagged once per endpoint that
            // returns it — into one finding that lists every affected endpoint (so it's counted + scored once).
            findings = collapseByRootCause(findings);

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
            final String svcName = req.serviceName() == null ? null : req.serviceName().trim();
            if (svcName != null && !svcName.isEmpty()) {
                // Match the prior scan on a NORMALIZED service name (trim + case-insensitive) so history is not lost
                // when the name differs only by whitespace/casing across builds, and pick the most recent qualifying
                // prior DETERMINISTICALLY (newest startedAt, ties broken by id) so the trend is stable run-to-run.
                scanRepository.findAllByOrderByStartedAtDesc().stream()
                        .filter(s -> s.getServiceName() != null && svcName.equalsIgnoreCase(s.getServiceName().trim()))
                        .filter(s -> s.getFidelityScore() != null && !s.getId().equals(current.getId()))
                        .max(java.util.Comparator
                                .comparing(Scan::getStartedAt,
                                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                                .thenComparing(s -> s.getId() == null ? "" : s.getId()))
                        .ifPresent(prev -> current.setPreviousFidelityScore(prev.getFidelityScore()));
            }

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
                    if (f.getAiDisputeReason() != null) {
                        toTranslate.add(f.getAiDisputeReason());
                    }
                }
                if (scan.getBlindSpots() != null) {
                    toTranslate.add(scan.getBlindSpots());
                }
                if (!toTranslate.isEmpty()) {
                    fr = translationService.toFrench(toTranslate, req.owner());
                }
            }
            // Persist the translation map so a later LIVE re-render (ReportController) stays bilingual instead of
            // falling back to English for the finding bodies.
            if (!fr.isEmpty()) {
                try {
                    scan.setTranslationsJson(objectMapper.writeValueAsString(fr));
                } catch (Exception ex) {
                    log.debug("Scan {} could not persist translations: {}", scan.getId(), ex.getMessage());
                }
            }

            String html = reportRenderer.renderHtml(scan, findings, fr);
            // Human-readable artifact names: contract-report-<service>-<model>-<date> instead of the opaque scan UUID.
            // ReportController reconstructs the same name (from the scan) for its on-disk fallback.
            String reportBase = ca.bnc.qe.veritas.report.ReportNaming.baseName(scan);
            String reportPath = writeOut(reportBase + ".html", html);
            String reportPdfPath = null;
            try {
                reportPdfPath = writeOutBytes(reportBase + ".pdf",
                        reportRenderer.renderPdf(scan, findings, fr));
            } catch (Exception ex) {
                log.warn("PDF report skipped: {}", ex.getMessage());
            }
            scan.setStatus(RunStatus.COMPLETED);
            scan.setStage(ScanStages.DONE);
            scan.setFinishedAt(Instant.now());
            // Atomic: the findings and the COMPLETED scan are written together (one transaction).
            scanPersistence.complete(scan, findings, enrich);
            log.info("Scan {} [{}] → DONE — {} finding(s), fidelity {}/100",
                    scan.getId(), scan.getServiceName(), findings.size(), scan.getFidelityScore());

            return new ValidationResult(scan.getId(), scan.getStatus().name(), findings.size(),
                    bySeverity(findings), reportPath, reportPdfPath, correctedYamlPath, scan.getTotalEstCostUsd());
        } catch (Exception ex) {
            log.error("Scan {} [{}] → FAILED — {}", scan.getId(), req.serviceName(), ex.getMessage(), ex);
            scan.setFailedStage(scan.getStage());   // preserve WHERE it failed before stage is clobbered to FAILED
            scan.setStatus(RunStatus.FAILED);
            scan.setStage(ScanStages.FAILED);
            scan.setStageDetail(null);   // a failed scan must not keep a stale "Generating…" sub-line
            scan.setErrorMessage(ex.getMessage());
            scan.setFinishedAt(Instant.now());
            persist(scan);   // if the reconciler already finalized this scan, don't fight it
            return new ValidationResult(scan.getId(), "FAILED", findings.size(),
                    bySeverity(findings), null, null, null, scan.getTotalEstCostUsd());
        }
    }

    private record ReconcileResult(String correctedYaml, Map<String, JsonNode> enrich, CostResult cost,
                                   List<Finding> llmFindings, Double confidence, String blindSpots,
                                   Map<String, String> disputes) {}

    /**
     * Compact structured projection of one or more {@link ApiModel}s for the reconcile prompt: every endpoint with its
     * params (name + exact {@code in} location + type + required + constraints), request/response body schema refs and
     * media types, plus the referenced DTO schemas (fields + constraints). Source refs/line numbers are dropped to keep
     * it terse. This is what lets the LLM name params by their real location and build the corrected YAML from the
     * actual DTO shapes — replacing the old terse {@code "VERB /path"} signature lists it had to infer from.
     */
    static Map<String, Object> apiEvidence(List<ApiModel> models) {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        Map<String, Object> schemas = new LinkedHashMap<>();
        for (ApiModel m : models) {
            if (m == null) {
                continue;
            }
            for (Endpoint e : m.endpoints()) {
                endpoints.add(endpointEvidence(e));
            }
            for (Map.Entry<String, SchemaModel> en : m.schemas().entrySet()) {
                schemas.putIfAbsent(en.getKey(), schemaEvidence(en.getValue()));   // first model wins on name clash
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoints", endpoints);
        out.put("schemas", schemas);
        return out;
    }

    private static Map<String, Object> endpointEvidence(Endpoint e) {
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("method", e.method().name());
        ep.put("path", e.pathTemplate());
        if (e.params() != null && !e.params().isEmpty()) {
            List<Map<String, Object>> ps = new ArrayList<>();
            for (ParamModel p : e.params()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name());
                pm.put("in", p.location().name().toLowerCase(java.util.Locale.ROOT));   // path|query|header|cookie
                if (p.type() != null) {
                    pm.put("type", p.type());
                }
                pm.put("required", p.required());
                putConstraints(pm, p.constraints());
                ps.add(pm);
            }
            ep.put("params", ps);
        }
        if (e.requestBody() != null) {
            Map<String, Object> rb = new LinkedHashMap<>();
            if (e.requestBody().schemaRef() != null) {
                rb.put("schema", e.requestBody().schemaRef());
            }
            rb.put("required", e.requestBody().required());
            if (e.requestBody().mediaTypes() != null && !e.requestBody().mediaTypes().isEmpty()) {
                rb.put("mediaTypes", e.requestBody().mediaTypes());
            }
            ep.put("requestBody", rb);
        }
        if (e.responses() != null && !e.responses().isEmpty()) {
            List<Map<String, Object>> rs = new ArrayList<>();
            for (ResponseModel r : e.responses()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("status", r.statusCode());
                if (r.schemaRef() != null) {
                    rm.put("schema", r.schemaRef());
                }
                if (r.mediaTypes() != null && !r.mediaTypes().isEmpty()) {
                    rm.put("mediaTypes", r.mediaTypes());
                }
                rs.add(rm);
            }
            ep.put("responses", rs);
        }
        if (e.consumes() != null && !e.consumes().isEmpty()) {
            ep.put("consumes", e.consumes());
        }
        if (e.produces() != null && !e.produces().isEmpty()) {
            ep.put("produces", e.produces());
        }
        if (e.security() != null && !e.security().isEmpty()) {
            ep.put("security", e.security());
        }
        return ep;
    }

    private static Map<String, Object> schemaEvidence(SchemaModel s) {
        Map<String, Object> sm = new LinkedHashMap<>();
        if (s.type() != null) {
            sm.put("type", s.type());
        }
        if (s.enumValues() != null && !s.enumValues().isEmpty()) {
            sm.put("enum", s.enumValues());
        }
        if (s.fields() != null && !s.fields().isEmpty()) {
            List<Map<String, Object>> fs = new ArrayList<>();
            for (FieldModel f : s.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.jsonName());
                fm.put("type", f.refSchema() != null ? f.refSchema() : f.type());
                if (f.format() != null) {
                    fm.put("format", f.format());
                }
                if (f.required()) {
                    fm.put("required", true);
                }
                putConstraints(fm, f.constraints());
                fs.add(fm);
            }
            sm.put("fields", fs);
        }
        return sm;
    }

    /**
     * Append the non-empty constraints to {@code target}. Crucially, an enum is emitted under {@code "enum"} only when
     * it is a FORMAL schema/Java enum; a set parsed from a parameter's DESCRIPTION prose goes under
     * {@code "documentedValues"} — so the LLM cannot over-claim that the spec "restricts" a value set that is merely
     * documented (the provenance the deterministic engine already tracks via {@code ConstraintSet.enumFromDescription}).
     */
    private static void putConstraints(Map<String, Object> target, ConstraintSet c) {
        if (c == null || c.isEmpty()) {
            return;
        }
        if (c.minLength() != null) {
            target.put("minLength", c.minLength());
        }
        if (c.maxLength() != null) {
            target.put("maxLength", c.maxLength());
        }
        if (c.minimum() != null) {
            target.put("minimum", c.minimum());
        }
        if (c.maximum() != null) {
            target.put("maximum", c.maximum());
        }
        if (Boolean.TRUE.equals(c.exclusiveMin())) {
            target.put("exclusiveMin", true);
        }
        if (Boolean.TRUE.equals(c.exclusiveMax())) {
            target.put("exclusiveMax", true);
        }
        if (c.pattern() != null) {
            target.put("pattern", c.pattern());
        }
        if (c.format() != null) {
            target.put("format", c.format());
        }
        if (c.enumValues() != null && !c.enumValues().isEmpty()) {
            target.put(c.enumFromDescription() ? "documentedValues" : "enum", c.enumValues());
        }
    }

    private ReconcileResult reconcile(ApiModel code, List<ApiModel> specs, List<Finding> findings,
                                      String owner, Scan scan, SpecPresence presence, ModelTier tier) throws Exception {
        String scanId = scan.getId();
        List<Map<String, String>> brief = new ArrayList<>();
        for (Finding f : findings) {
            brief.add(Map.of("findingId", f.getFindingId(), "type", f.getType().name(), "summary", nz(f.getSummary())));
        }
        // Structured, per-endpoint extracted models (params with exact location/type, request/response schemas, DTO
        // fields + constraints) — so the LLM names params by their real location and builds the corrected YAML from
        // the actual DTO shapes instead of inferring them from terse "VERB /path" signatures.
        String codeApi = objectMapper.writeValueAsString(apiEvidence(List.of(code)));
        String specApi = objectMapper.writeValueAsString(apiEvidence(specs));
        // The real endpoint paths (code + spec) — an endpoint-scoped design finding about anything else is unverifiable.
        java.util.Set<String> knownPaths = new HashSet<>();
        code.endpoints().forEach(e -> knownPaths.add(e.pathTemplate().toLowerCase(java.util.Locale.ROOT)));
        specs.forEach(s -> s.endpoints().forEach(e -> knownPaths.add(e.pathTemplate().toLowerCase(java.util.Locale.ROOT))));
        // What the extractor actually parsed/resolved — so the LLM never claims a source it has is "not supplied".
        String manifest = objectMapper.writeValueAsString(Map.of(
                "parsedEndpoints", code.endpoints().size(),
                "resolvedTypes", new ArrayList<>(code.schemas().keySet()),
                "knownGaps", code.blindSpots() == null ? List.of() : code.blindSpots()));

        String outputContract = "Code is the source of truth for behaviour. For each finding add a short "
                + "explanation and a proposed fix; then produce a reconciled corrected OpenAPI YAML (code wins on "
                + "behaviour). FRAMING BY FINDING TYPE: additive/non-breaking drift where the code is a compatible "
                + "superset of the spec (a field the code returns that the spec omits, an extra endpoint/param/status, "
                + "a dead-spec entry) is a DOCUMENTATION update, not a defect — say 'document X in the spec'. A "
                + "path-variable NAME mismatch (e.g. {app} vs {appId}) is POSITIONAL and non-breaking — frame it as a "
                + "naming-convention decision for the API owner (align the spec to the code, OR keep the spec's more "
                + "descriptive name and rename the code); do NOT prescribe changing the spec. ALSO add L5 "
                + "(design-quality) and L6 (test-basis adequacy) judgements in "
                + "designFindings — e.g. a spec with no examples/constraints/error responses is a weak test basis. "
                + "When a design finding is about a specific code DTO field or endpoint, include an `evidence` pointer to "
                + "it: `{\"codeSchema\": <a CODE_API schema name>, \"codeField\": <that schema's field jsonName>}` for a "
                + "field, and/or `{\"endpoint\": <a CODE_API endpoint>}`; omit `evidence` entirely if it is a spec-wide "
                + "observation. Only point at schema/field/endpoint names that appear in CODE_API — never invent one. "
                + "SPEC_PRESENCE_FACTS reports what the FULLY-RESOLVED spec actually contains (examples, schema "
                + "properties, constraints and error responses are resolved through $ref); NEVER assert any of these "
                + "is absent when the facts say it is present. "
                + "PARSED_SOURCE_MANIFEST lists the endpoints/types Veritas parsed and resolved; the only genuine gaps "
                + "are in its knownGaps. NEVER state a source/handler/DTO/security input was 'not supplied' unless it "
                + "appears in knownGaps. "
                + "CODE_API and SPEC_API are the STRUCTURED extracted models: every endpoint lists its params with the "
                + "exact location (in: path|query|header|cookie), type and required flag, its request/response body "
                + "schemas and media types, plus the referenced DTO schemas (fields + constraints). Name each parameter "
                + "by its CODE_API `in` value — a header param is a HEADER parameter, never a 'query parameter'. A value "
                + "set under `documentedValues` is documented in prose only: describe it as documented, never as the spec "
                + "restricting or enforcing an enum; only a set under `enum` is a formal schema enum. Build the corrected "
                + "YAML schemas from these structured DTOs, not from guesses. "
                + "If a DETERMINISTIC_FINDINGS item looks like a FALSE POSITIVE that the CODE_API/SPEC_API evidence "
                + "contradicts, do NOT silently accept it: list it in disputedFindings with its findingId and a "
                + "one-sentence code-grounded reason. Only dispute a findingId that appears in DETERMINISTIC_FINDINGS; "
                + "never invent ids; a dispute REQUIRES a concrete reason. Veritas keeps every disputed finding visible "
                + "to a human and only moves it out of the automatic release-blocking gate — it is never deleted. "
                + "In selfReview, report your overall confidence (0-100 integer) that the corrected spec matches the "
                + "code, and list any blind spots you hit (sources you could not fully see). "
                + "Do NOT add citations — Veritas attaches the governing standard (OpenAPI / HTTP / JSON Schema) "
                + "deterministically. Reply with exactly one fenced ```json block as the LAST thing, matching: "
                + "{\"correctedYaml\": string, \"findings\": [{\"findingId\": string, \"explanation\": string, "
                + "\"proposedFix\": string}], \"designFindings\": [{\"layer\": \"L5\"|\"L6\", \"severity\": string, "
                + "\"endpoint\": string, \"summary\": string, \"explanation\": string, "
                + "\"evidence\": {\"codeSchema\": string, \"codeField\": string, \"endpoint\": string}}], "
                + "\"disputedFindings\": [{\"findingId\": string, \"reason\": string}], "
                + "\"selfReview\": {\"confidence\": number, \"blindSpots\": [string]}}. No prose after the json.";

        // Chunk-and-merge: a large finding set would otherwise have its middle elided by the prompt cap.
        // Batch the findings (deterministic) and merge the per-finding enrichment; the corrected YAML and design
        // findings come from the first batch (it carries the full endpoint context). Small scans → one call.
        List<List<Map<String, String>>> batches = partitionByTokens(brief, batchInputTokens);
        String model = modelSelector.resolveTier(tier);   // per-scan thoroughness (ECONOMY/STANDARD/DEEP)
        scan.setModel(model);   // surfaced live so the user sees which Copilot model is doing the AI step
        detail(scan, "Reviewing " + findings.size() + " finding" + (findings.size() == 1 ? "" : "s")
                + " and drafting a corrected spec…");

        Map<String, JsonNode> enrich = new HashMap<>();
        Map<String, String> disputes = new HashMap<>();   // findingId -> AI dispute reason (bounded to ids we sent)
        List<Finding> design = new ArrayList<>();
        Set<String> designIds = new HashSet<>();
        String correctedYaml = null;
        Double confidence = null;
        List<String> blind = new ArrayList<>();
        // If the structured evidence is larger than one untrusted block holds, PromptComposer middle-elides it — surface
        // that, so a large service's AI review / corrected spec isn't silently built from partial CODE_API/SPEC_API.
        int evidenceBudget = promptComposer.contextBudgetChars();
        if (evidenceBudget > 0 && (codeApi.length() > evidenceBudget || specApi.length() > evidenceBudget)) {
            blind.add("The structured code/spec evidence (CODE_API/SPEC_API) exceeded the model context budget and was "
                    + "truncated; the AI review and corrected spec for this service may be built from partial evidence.");
        }
        double premium = 0;
        double costUsd = 0;
        long tokIn = 0;
        long tokOut = 0;
        BillingMode mode = null;

        int batchNo = 0;
        for (List<Map<String, String>> batch : batches) {
            batchNo++;
            if (batches.size() > 1) {
                log.info("Scan {} reconcile batch {}/{} ({} finding(s))…", scanId, batchNo, batches.size(), batch.size());
            }
            String batchJson = objectMapper.writeValueAsString(batch);
            Set<String> batchIds = new HashSet<>();   // the deterministic ids sent THIS batch — disputes are bounded to them
            for (Map<String, String> m : batch) {
                String id = m.get("findingId");
                if (id != null) {
                    batchIds.add(id);
                }
            }
            String inputs = promptComposer.data("DETERMINISTIC_FINDINGS", batchJson)
                    + promptComposer.data("CODE_API", codeApi)
                    + promptComposer.data("SPEC_API", specApi)
                    + promptComposer.data("SPEC_PRESENCE_FACTS", objectMapper.writeValueAsString(presence))
                    + promptComposer.data("PARSED_SOURCE_MANIFEST", manifest);
            String prompt = promptComposer.compose("[CONTRACT-RECONCILE]", "validate-service-contract.prompt.md",
                    Set.of("1", "6", "12"), inputs, outputContract, modelSelector.promptTokenCap(model));
            if (!detail(scan, batches.size() > 1
                    ? "Reviewing findings — batch " + batchNo + " of " + batches.size()
                    : "Generating the corrected spec — the AI is writing the fix…")) {
                log.warn("Scan {} finalized externally — stopping reconcile before further LLM calls", scanId);
                break;   // the scan was failed under us (e.g. timed out); don't burn more Copilot spend on a dead scan
            }
            // Live progress: as the (streamed) reply grows, update the scan's stageDetail so the dashboard shows the
            // AI is actively writing — throttled (ms + chars) and routed through the same version-synced detail()
            // path. A no-op for the mock/non-streaming gateways (they never call reportProgress). Cleared in finally.
            callContext.armProgressSink(reconcileProgressSink(scan, batchNo, batches.size()));
            String raw;
            try {
                raw = llm.complete(prompt, model);
            } finally {
                callContext.clearProgressSink();
            }
            CostResult c = costRecorder.record("validate-contract", "reconcile", model, prompt, raw, owner, scanId);   // bill before parse
            JsonNode node = objectMapper.readTree(jsonExtractor.extract(raw));
            schemaValidator.validate(node, "contract-reconcile.schema.json");
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
            // AI dispute channel: honour ONLY ids we actually sent this batch and only with a concrete reason — never
            // trust an id the engine never emitted (blocks fabricated / design-finding ids). Non-destructive downstream.
            for (JsonNode dn : node.path("disputedFindings")) {
                if (!dn.hasNonNull("findingId") || !dn.hasNonNull("reason")) {
                    continue;
                }
                String id = dn.get("findingId").asText();
                String reason = dn.get("reason").asText();
                if (reason.isBlank()) {
                    continue;
                }
                if (!batchIds.contains(id)) {
                    log.info("Scan {} ignoring AI dispute for unknown finding id {} (not sent this batch)", scanId, id);
                    continue;
                }
                disputes.putIfAbsent(id, reason);
            }
            for (Finding df : parseDesignFindings(node, knownPaths, code)) {
                // Suppress an AI absence claim the resolved spec contradicts (e.g. "no examples" when $ref examples
                // exist). Only for SPEC-WIDE claims: a finding scoped to a specific endpoint can't be refuted by
                // spec-global "any..." presence facts — that endpoint may genuinely lack the thing.
                boolean specWide = df.getEndpoint() == null || df.getEndpoint().isBlank();
                if (specWide && presence.contradictsAbsenceClaim(df.getSummary())) {
                    log.info("Scan {} suppressing contradicted spec-wide design finding: {}", scanId, df.getSummary());
                    continue;
                }
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
                Math.round(costUsd * 10_000.0) / 10_000.0, false);   // aggregate across batches; per-batch ledger rows carry the real flag
        String blindSpots = blind.isEmpty() ? null : String.join("; ", blind);
        return new ReconcileResult(correctedYaml, enrich, cost, design, confidence, blindSpots, disputes);
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
    private List<Finding> parseDesignFindings(JsonNode node, java.util.Set<String> knownPaths, ApiModel code) {
        List<Finding> out = new ArrayList<>();
        for (JsonNode d : node.path("designFindings")) {
            String layer = d.path("layer").asText("L5");
            boolean l6 = "L6".equalsIgnoreCase(layer);
            FindingType type = l6 ? FindingType.TEST_BASIS_GAP : FindingType.DESIGN_QUALITY;
            String summary = d.path("summary").asText("");
            String endpoint = d.hasNonNull("endpoint") ? d.path("endpoint").asText() : null;
            // An endpoint-scoped finding about an endpoint Veritas never parsed is unverifiable — keep it (for the
            // record) but cap it to INFO and flag it, so a fabricated per-endpoint BLOCKER can't ship in the board PDF.
            boolean phantom = endpoint != null && !endpoint.isBlank() && !isKnownEndpoint(endpoint, knownPaths);
            Severity severity = phantom ? Severity.INFO : parseSeverity(d.path("severity").asText(null));
            String explanation = d.hasNonNull("explanation") ? d.path("explanation").asText() : null;
            // Only flag the explanation when the endpoint actually LOOKS like an HTTP endpoint but wasn't parsed
            // (a genuine "GET /fake" hallucination). A descriptive pseudo-locus ("policies schema (both endpoints)")
            // is a legitimate design/test-coverage label — keep it clean and don't leak the guard into the report.
            if (phantom && isHttpEndpoint(endpoint)) {
                explanation = "[unverified endpoint — '" + endpoint + "' is not a parsed API endpoint] "
                        + (explanation == null ? "" : explanation);
            }
            Finding finding = Finding.builder()
                    .findingId(Integer.toHexString(java.util.Objects.hash(type, summary, endpoint)))
                    .type(type)
                    .layer(l6 ? Layer.L6 : Layer.L5)
                    .severity(severity)
                    .confidence(Confidence.MEDIUM)
                    .origin("LLM")
                    .endpoint(endpoint)
                    .specSource("code-vs-spec")
                    .summary(summary)
                    .explanation(explanation)
                    .citation(d.hasNonNull("citation") ? d.path("citation").asText() : null)
                    .build();
            // Closed-world: bind evidence to the parsed code model when — and only when — it resolves exactly.
            // Never mutates findingId (evidence/locus are NOT hash inputs), never fabricates a location.
            out.add(bindDesignEvidence(finding, d.path("evidence"), code));
        }
        return out;
    }

    /**
     * Attach deterministic, verified evidence to a design finding, closed-world against the parsed {@code code} model —
     * never fabricated. Precedence:
     * <ol>
     *   <li>An LLM {@code evidence} pointer {@code {codeSchema, codeField}}: resolve the schema in {@code code.schemas()},
     *       then the field by {@code jsonName}; bind that field's {@link SourceRef} + {@code specLocus = codeSchema#codeField}
     *       only if the field has a source. A schema/field that does not resolve binds nothing.</li>
     *   <li>Else a minimal, safe fallback: if the finding's {@code endpoint} value exactly names a known code schema with a
     *       schema-level {@link SourceRef}, bind that + {@code specLocus = schemaName}.</li>
     * </ol>
     * Returns the finding unchanged when nothing resolves — a genuinely spec-wide finding legitimately keeps null evidence.
     * Does not touch {@code findingId}: {@code codeEvidence}/{@code specLocus} are never part of the id hash.
     */
    private static Finding bindDesignEvidence(Finding finding, JsonNode evidence, ApiModel code) {
        if (code == null || code.schemas() == null || code.schemas().isEmpty()) {
            return finding;   // nothing to resolve against
        }
        // 1) Explicit code-field pointer — bind only when the exact schema AND field resolve and the field has a source.
        if (evidence != null && evidence.hasNonNull("codeSchema") && evidence.hasNonNull("codeField")) {
            String codeSchema = evidence.get("codeSchema").asText();
            String codeField = evidence.get("codeField").asText();
            SchemaModel schema = code.schemas().get(codeSchema);
            if (schema != null && schema.fields() != null) {
                for (FieldModel field : schema.fields()) {
                    if (field.jsonName() != null && field.jsonName().equals(codeField) && field.source() != null) {
                        return finding.toBuilder()
                                .codeEvidence(field.source())
                                .specLocus(codeSchema + "#" + codeField)
                                .build();
                    }
                }
            }
            return finding;   // pointer given but did not resolve → bind nothing (never a wrong location)
        }
        // 2) Minimal fallback: the finding's endpoint value exactly names a known code schema with a schema-level source.
        String endpoint = finding.getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            SchemaModel schema = code.schemas().get(endpoint);
            if (schema != null && schema.source() != null) {
                return finding.toBuilder()
                        .codeEvidence(schema.source())
                        .specLocus(endpoint)
                        .build();
            }
        }
        return finding;
    }

    /** Whether a design finding's endpoint string references a real (parsed) endpoint path — lenient substring match. */
    private static boolean isKnownEndpoint(String endpoint, java.util.Set<String> knownPaths) {
        if (knownPaths.isEmpty()) {
            return true;   // nothing to verify against → don't second-guess
        }
        String e = endpoint.toLowerCase(java.util.Locale.ROOT);
        for (String p : knownPaths) {
            if (!p.isBlank() && e.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private Severity parseSeverity(String s) {
        try {
            // Unknown/blank severity → INFO, never MAJOR: a fabricated finding with no severity must not read as a
            // hard defect on the board report.
            return s == null || s.isBlank() ? Severity.INFO : Severity.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Severity.INFO;
        }
    }

    /** Prefer the LLM-reconciled corrected YAML when it re-parses AND preserves the code's endpoints; else the
     *  deterministic code-wins spec. */
    private String chooseCorrectedYaml(String llmCorrected, ApiModel code, String title, String originalSpecYaml) {
        if (llmCorrected != null && roundTrips(llmCorrected) && preservesEndpoints(llmCorrected, code)) {
            // The LLM output governs paths/schemas, but its info/servers are its own invention (it is never handed the
            // spec's info/servers) — overlay the real metadata so the "drop-in replacement" is honest. The deterministic
            // build() already preserves it internally; this makes the LLM-preferred branch match.
            return correctedSpecBuilder.withOriginalMetadata(llmCorrected, originalSpecYaml);
        }
        String deterministic = correctedSpecBuilder.build(code, title, originalSpecYaml);
        return roundTrips(deterministic) ? deterministic : null;
    }

    /** A short, human-readable reason from an exception chain — the root cause's message (or type), capped. */
    private static String shortReason(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = root.getClass().getSimpleName();
        }
        return msg.length() > 140 ? msg.substring(0, 140) + "…" : msg;
    }

    /**
     * The LLM "corrected" spec must not silently DROP an endpoint the code declares (a well-formed spec that quietly
     * removes a real route is worse than the deterministic fallback). When the corrected spec exposes no endpoints we
     * can't verify it, so we don't second-guess it. A parse-only gate ({@link #roundTrips}) never caught this.
     */
    private boolean preservesEndpoints(String yaml, ApiModel code) {
        try {
            ApiModel corrected = openApiModelExtractor.extract("corrected-check", yaml).model();
            if (corrected == null || corrected.endpoints().isEmpty()) {
                return true;
            }
            java.util.Set<String> sigs = new HashSet<>();
            corrected.endpoints().forEach(e -> sigs.add(e.signature().toUpperCase(java.util.Locale.ROOT)));
            for (Endpoint e : code.endpoints()) {
                if (!sigs.contains(e.signature().toUpperCase(java.util.Locale.ROOT))) {
                    log.warn("Rejecting LLM corrected spec — it drops endpoint {} the code declares; "
                            + "falling back to the deterministic code-wins spec.", e.signature());
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
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

    /** Static null-safe empty-default — the collapse keys ({@link #rootCauseKey}/{@link #codeLocusKey}) are static. */
    private static String nzs(String s) {
        return s == null ? "" : s;
    }

    /**
     * Collapse findings that describe the same endpoint+issue (keyed on type + endpoint + normalized summary),
     * order-preserving. When a deterministic and an LLM finding collide, keep the deterministic one (it carries the
     * code evidence and is scored) but graft the LLM's explanation/proposed fix onto it.
     */
    static List<Finding> dedupCrossList(List<Finding> in) {
        Map<String, Finding> byKey = new LinkedHashMap<>();
        for (Finding f : in) {
            String key = dedupKey(f);
            Finding existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, f);
            } else if (!isDeterministic(existing) && isDeterministic(f)) {
                byKey.put(key, mergeEnrichment(f, existing));
            }
            // otherwise keep the first (order-preserving); a later duplicate is dropped
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Graft the LLM per-finding enrichment (explanation / proposed fix) and any AI dispute onto the in-memory findings
     * so the as-scanned disk report matches the live re-render (which reads the same enrichment from the persisted
     * row). Runs BEFORE {@link #collapseByRootCause} so each finding is looked up under the id the LLM actually saw —
     * a spec-keyed collapse re-fingerprints its survivor, so grafting afterwards would always miss the shared-schema
     * case. Package-private + static so the real ordering (dedup → graft → collapse) is directly testable.
     */
    static List<Finding> graftEnrichment(List<Finding> findings, Map<String, JsonNode> enrich,
            Map<String, String> disputes) {
        return findings.stream().map(f -> {
            JsonNode e = enrich.get(f.getFindingId());
            String disputeReason = disputes.get(f.getFindingId());
            // The AI may only DOWNGRADE a hard deterministic finding (one that is currently counted/blocking) —
            // never a design/LLM/low-confidence item (already not counted), and never an escalation. Severity is
            // left intact; the finding stays listed. This is the only place a dispute takes effect.
            boolean dispute = disputeReason != null && "DETERMINISTIC".equalsIgnoreCase(nzs(f.getOrigin()))
                    && !ca.bnc.qe.veritas.report.FidelityScore.isNeedsAttention(f);
            if (e == null && !dispute) {
                return f;
            }
            var b = f.toBuilder();
            if (e != null && f.getExplanation() == null && e.hasNonNull("explanation")) {
                b.explanation(e.get("explanation").asText());
            }
            if (e != null && f.getProposedFix() == null && e.hasNonNull("proposedFix")) {
                b.proposedFix(e.get("proposedFix").asText());
            }
            if (dispute) {
                b.aiDisputed(true).aiDisputeReason(disputeReason);
            }
            return b.build();
        }).toList();
    }

    /** Schema-field finding types whose ROOT CAUSE is a single spec-schema field (not a per-endpoint code line): the
     *  same shared spec schema $ref'd by several endpoints is one spec edit, so these collapse on the spec locus. */
    private static final Set<FindingType> SPEC_LOCUS_TYPES = Set.of(
            FindingType.SCHEMA_FIELD_MISSING, FindingType.SCHEMA_FIELD_EXTRA, FindingType.SCHEMA_FIELD_TYPE_MISMATCH);

    /**
     * Collapse findings that share the SAME ROOT CAUSE across endpoints into one finding that lists every endpoint
     * involved, so one shared defect is counted (and scored) once instead of per endpoint. The first occurrence
     * survives (keeping its evidence) and gains an {@code affectedEndpoints} list. Order-preserving.
     *
     * <p>Two roots are recognised (see {@link #rootCauseKey}):
     * <ul>
     *   <li><b>Spec locus</b> — for {@code SCHEMA_FIELD_MISSING/EXTRA/TYPE_MISMATCH} that carry a {@code specLocus}
     *       ({@code "<specSchemaName>#<field>"}, plus a mismatch-kind token for TYPE_MISMATCH so two DIFFERENT type
     *       defects on one spec field never merge): the fix is ONE edit to the shared spec schema, so two endpoints
     *       returning DIFFERENT code DTOs that both $ref the same spec schema (their code-evidence locations differ,
     *       so a code-locus key can never collapse them) merge here. The survivor's summary is rewritten to name the
     *       shared spec schema for MISSING/EXTRA; TYPE_MISMATCH keeps its original wording (the merge just
     *       de-duplicates the score charge). A multi-endpoint spec-keyed survivor is re-fingerprinted on the root
     *       cause itself (type + specSource + specLocus) — the first occurrence's id hashes its endpoint, so keeping
     *       it would reset a reviewer's disposition whenever controller enumeration order changes between scans.</li>
     *   <li><b>Code locus</b> — the fallback: type + specSource + the exact code evidence location + line + the
     *       normalized summary (a shared DTO field flagged once per endpoint that returns it). {@code specSource} is
     *       part of the key so the SAME code line diffed against two different specs stays two findings (closing a
     *       latent cross-spec over-merge). The summary keeps DISTINCT defects apart: endpoint-level findings of one
     *       type all share the controller METHOD's SourceRef, so without it an undocumented 500 and an undocumented
     *       406 on one endpoint would share a key and the second would be silently dropped.</li>
     * </ul>
     * {@code CONSTRAINT_GAP} is DELIBERATELY excluded from the spec-locus family: per-endpoint constraint values can
     * legitimately differ (a code-side assertion on one endpoint only), so collapsing them would hide a real defect —
     * it falls through to the code-locus key like any other type. Findings with neither anchor (no code evidence and
     * no specLocus — e.g. spec-side-only findings) are passed through untouched.
     */
    static List<Finding> collapseByRootCause(List<Finding> in) {
        Map<String, List<String>> endpointsByRoot = new LinkedHashMap<>();
        for (Finding f : in) {
            String key = rootCauseKey(f);
            if (key == null || f.getEndpoint() == null) {
                continue;
            }
            // A spec-keyed ("S|") merge can pull a components-loop finding (endpoint like "Order.total") in with the
            // per-endpoint ones — but a schema locus is not an HTTP endpoint, so accruing it into affectedEndpoints
            // would falsely claim a second endpoint. Exclude non-HTTP loci from the S| accrual; with it filtered the
            // duplicate is still dropped and the score still charges once, but no false multi-endpoint claim is made.
            if (key.startsWith("S|") && !isHttpEndpoint(f.getEndpoint())) {
                continue;
            }
            List<String> eps = endpointsByRoot.computeIfAbsent(key, k -> new ArrayList<>());
            if (!eps.contains(f.getEndpoint())) {
                eps.add(f.getEndpoint());
            }
        }
        List<Finding> out = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (Finding f : in) {
            String key = rootCauseKey(f);
            if (key == null) {
                out.add(f);
                continue;
            }
            if (!emitted.add(key)) {
                continue;   // a later finding for a root cause already emitted
            }
            List<String> eps = endpointsByRoot.getOrDefault(key, List.of());
            if (eps.size() <= 1) {
                out.add(f);
                continue;
            }
            var b = f.toBuilder().affectedEndpoints(List.copyOf(eps));
            if (key.startsWith("S|")) {
                // Order-independent fingerprint for a spec-keyed multi-endpoint merge: the first occurrence's id
                // hashes its OWN endpoint, so file-walk order would decide the surviving fingerprint and a reviewer's
                // disposition would silently reset whenever controller enumeration order changes between scans.
                b.findingId(Integer.toHexString(
                        java.util.Objects.hash(f.getType(), f.getSpecSource(), f.getSpecLocus())));
            }
            out.add(rewriteSpecSchemaSummary(b.build(), key));
        }
        return out;
    }

    /** Root-cause key for {@link #collapseByRootCause}. Spec-keyed ("S|…") for a schema-field type that carries a
     *  {@code specLocus}; otherwise the code-locus fallback ("C|…"), or null when there is nothing to anchor on. */
    static String rootCauseKey(Finding f) {
        if (SPEC_LOCUS_TYPES.contains(f.getType()) && f.getSpecLocus() != null) {
            return "S|" + f.getType() + "|" + nzs(f.getSpecSource()) + "|" + f.getSpecLocus();
        }
        return codeLocusKey(f);
    }

    /** An {@code affectedEndpoints} entry is a real HTTP endpoint ("METHOD /path") — not a schema locus like
     *  "Order.total" a components-loop finding carries. Used to keep non-HTTP loci out of a spec-keyed merge's
     *  endpoint accrual (else a shared-schema survivor falsely claims a schema locus as a second endpoint). */
    private static final Pattern HTTP_ENDPOINT =
            Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)\\s");

    static boolean isHttpEndpoint(String endpoint) {
        return endpoint != null && HTTP_ENDPOINT.matcher(endpoint).find();
    }

    /** Code-locus key: type + specSource + the exact code evidence location + line + normalized summary, or null when
     *  there is no code evidence to anchor on (never collapse those). {@code specSource} is included so the same code
     *  line diffed against two different specs stays two findings (a multi-spec scan must not silently imply one spec
     *  is clean). The normalized summary is included because endpoint-level findings of one type all carry the SAME
     *  controller-method SourceRef — without it, two DIFFERENT defects on one endpoint (an undocumented 500 AND 406,
     *  or two missing params) would share a key and the second would be silently dropped. */
    static String codeLocusKey(Finding f) {
        if (f.getCodeEvidence() == null || f.getCodeEvidence().location() == null) {
            return null;
        }
        Integer line = f.getCodeEvidence().startLine();
        return "C|" + f.getType() + "|" + nzs(f.getSpecSource()) + "|" + f.getCodeEvidence().location()
                + "|" + (line == null ? "" : line) + "|" + normSummary(f.getSummary());
    }

    /** Rewrite a spec-keyed multi-endpoint survivor's summary to name the SHARED spec schema (so it no longer reads as
     *  a single endpoint's finding). Only for MISSING/EXTRA — TYPE_MISMATCH keeps its original per-endpoint wording. */
    private static Finding rewriteSpecSchemaSummary(Finding survivor, String key) {
        if (!key.startsWith("S|")) {
            return survivor;
        }
        FindingType t = survivor.getType();
        if (t != FindingType.SCHEMA_FIELD_MISSING && t != FindingType.SCHEMA_FIELD_EXTRA) {
            return survivor;
        }
        String locus = survivor.getSpecLocus();
        // Split at the FIRST '#': an OpenAPI component-schema name can never contain '#' (its charset is
        // [A-Za-z0-9.\-_]) while a JSON field name CAN (e.g. @JsonProperty("card#number")) — so the schema is
        // everything before the first '#' and the field is everything after it.
        int cut = locus.indexOf('#');
        String schema = cut < 0 ? locus : locus.substring(0, cut);
        String field = cut < 0 ? locus : locus.substring(cut + 1);
        String summary = t == FindingType.SCHEMA_FIELD_MISSING
                ? "Field '" + field + "' of shared spec schema '" + schema + "' is in code but missing from the spec schema"
                : "Field '" + field + "' of shared spec schema '" + schema + "' is in the spec but not in code";
        return survivor.toBuilder().summary(summary).build();
    }

    /**
     * Dedup key. Includes specSource so the SAME discrepancy against two different specs (a supported multi-spec
     * scan) stays two findings — collapsing them would silently imply one spec is clean. Does NOT lowercase: HTTP
     * param/header names are case-sensitive, so case-only-distinct findings are genuinely different.
     */
    static String dedupKey(Finding f) {
        String endpoint = f.getEndpoint() == null ? "" : f.getEndpoint();
        String specSource = f.getSpecSource() == null ? "" : f.getSpecSource();
        return f.getType() + "|" + endpoint + "|" + specSource + "|" + normSummary(f.getSummary());
    }

    private static boolean isDeterministic(Finding f) {
        return "DETERMINISTIC".equalsIgnoreCase(nzs(f.getOrigin()));
    }

    /** Normalize whitespace + trailing punctuation only — never case (param/header names are case-sensitive). */
    static String normSummary(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").replaceAll("[.;:,]+$", "").trim();
    }

    /** Graft an LLM finding's enrichment onto the deterministic survivor without overwriting its own fields. */
    private static Finding mergeEnrichment(Finding deterministic, Finding llm) {
        var b = deterministic.toBuilder();
        if (deterministic.getExplanation() == null && llm.getExplanation() != null) {
            b.explanation(llm.getExplanation());
        }
        if (deterministic.getProposedFix() == null && llm.getProposedFix() != null) {
            b.proposedFix(llm.getProposedFix());
        }
        return b.build();
    }
}
