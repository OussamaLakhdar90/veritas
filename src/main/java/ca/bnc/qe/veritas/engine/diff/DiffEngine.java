package ca.bnc.qe.veritas.engine.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import org.springframework.stereotype.Component;

/**
 * Deterministic comparison of the canonical {@link ApiModel}s — no LLM. Produces L1–L4 + mechanical-L6
 * findings comparing code vs each spec, and spec-vs-spec drift (repo YAML vs Confluence YAML).
 * Code is the source of truth for behavior.
 */
@Component
public class DiffEngine {

    /** Code (source of truth) vs one spec. */
    public List<Finding> diffCodeVsSpec(ApiModel code, ApiModel spec) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Endpoint> codeByKey = indexByKey(code);
        Map<String, Endpoint> specByKey = indexByKey(spec);
        Map<String, Endpoint> codeByPath = indexByPath(code);
        Map<String, Endpoint> specByPath = indexByPath(spec);
        Map<String, Set<HttpMethod>> codeVerbsByPath = verbsByPath(code);
        Map<String, Set<HttpMethod>> specVerbsByPath = verbsByPath(spec);
        Set<String> verbMismatchPaths = new HashSet<>();   // report a path's verb divergence once
        // Distinct spec operations can collapse to the same normalized key (e.g. /orders/{orderId} and /orders/{id} →
        // "GET /orders/{}"); last-wins indexByKey then silently drops one. Surface the ambiguity rather than hide it.
        if (spec.endpoints().size() > specByKey.size()) {
            findings.add(finding(FindingType.EXTRA_ENDPOINT, "spec-key-collision", spec.source(),
                    "Two or more spec operations collapse to the same normalized signature (distinct path-variable "
                            + "names or letter-casing); code-vs-spec matching is ambiguous for them and they may not be "
                            + "compared reliably.", null, Confidence.LOW));
        }

        // L1 — a real spec must carry info.title + info.version (only checked for actual parsed specs).
        if (spec.openApiVersion() != null) {
            if (isBlank(spec.title())) {
                findings.add(finding(FindingType.MISSING_INFO_FIELD, null, spec.source(),
                        "Spec is missing info.title", null, Confidence.MEDIUM));
            }
            if (isBlank(spec.version())) {
                findings.add(finding(FindingType.MISSING_INFO_FIELD, null, spec.source(),
                        "Spec is missing info.version", null, Confidence.MEDIUM));
            }
        }

        for (Endpoint ce : code.endpoints()) {
            String key = key(ce);
            Endpoint se = specByKey.get(key);
            if (se != null) {
                compareMatched(findings, code, spec, ce, se);
                continue;
            }
            // same path, different verb → verb mismatch rather than "missing"
            String np = normPath(ce.pathTemplate());
            Endpoint sByPath = specByPath.get(np);
            if (sByPath != null) {
                verbMismatchPaths.add(np);
                findings.add(finding(FindingType.VERB_MISMATCH, label(ce), spec.source(),
                        "Code exposes " + ce.method() + " " + ce.pathTemplate()
                                + " but the spec defines " + specVerbsByPath.getOrDefault(np, Set.of())
                                + " for that path",
                        ce, Confidence.HIGH));
            } else {
                findings.add(finding(FindingType.MISSING_ENDPOINT, label(ce), spec.source(),
                        "Endpoint " + ce.signature() + " exists in code but is missing from the spec",
                        ce, Confidence.HIGH));
            }
        }
        // When the code extraction is known-incomplete — controllers delegate their @*Mapping to interfaces outside the
        // scanned sources (e.g. openapi-generator API interfaces), or the service uses functional RouterFunction /
        // Kotlin routing this extractor can't read — a spec endpoint "not found in code" is UNVERIFIABLE, not a dead
        // endpoint. Don't emit "dead spec?" findings we can't stand behind; surface one honest summary.
        boolean codeIncomplete = codeExtractionIncomplete(code);
        int unverifiable = 0;
        for (Endpoint se : spec.endpoints()) {
            if (codeByKey.containsKey(key(se))) {
                continue;   // matched by verb+path → already compared in the code-iteration loop
            }
            String np = normPath(se.pathTemplate());
            if (codeByPath.containsKey(np)) {
                // The PATH exists in code but not under this verb — the spec documents an operation the code does not
                // implement. Previously this was silently dropped (the path-only guard suppressed it even when the code
                // GET already matched a spec GET, hiding an unimplemented spec POST). Report it once per path (the code
                // iteration may already have flagged the converse direction for the same path).
                if (verbMismatchPaths.add(np)) {
                    findings.add(finding(FindingType.VERB_MISMATCH, se.signature(), spec.source(),
                            "Spec documents " + se.method() + " " + se.pathTemplate() + " but the code exposes "
                                    + codeVerbsByPath.getOrDefault(np, Set.of()) + " on that path",
                            null, Confidence.HIGH));
                }
            } else if (codeIncomplete) {
                unverifiable++;
            } else {
                findings.add(finding(FindingType.EXTRA_ENDPOINT, se.signature(), spec.source(),
                        "Endpoint " + se.signature() + " is in the spec but not found in code (dead spec?)",
                        null, Confidence.MEDIUM));
            }
        }
        if (unverifiable > 0) {
            findings.add(finding(FindingType.EXTRA_ENDPOINT, "code-extraction-incomplete", spec.source(),
                    unverifiable + " spec endpoint(s) could not be cross-checked against code because part of the "
                            + "routing is not statically analysable (controllers delegating their request mappings to "
                            + "interfaces outside the scanned sources such as openapi-generator API interfaces, "
                            + "functional RouterFunction routing, or Kotlin sources). Re-run with the full routing in "
                            + "scope to verify these — they are NOT being reported as dead spec.",
                    null, Confidence.LOW));
        }

        // schema field-level diff for schemas present (by name) on both sides
        for (Map.Entry<String, SchemaModel> e : code.schemas().entrySet()) {
            SchemaModel specSchema = spec.schemas().get(e.getKey());
            if (specSchema != null) {
                compareSchema(findings, spec.source(), e.getKey(), e.getValue(), specSchema);
            }
        }
        return dedup(findings);
    }

    /** Stable key so a parameter is matched only against the spec parameter in the SAME location. */
    private static String paramKey(ParamModel p) {
        String loc = p.location() == null ? "?" : p.location().name();
        String name = p.name() == null ? "" : p.name();
        // HTTP header field names are case-INSENSITIVE (RFC 9110 §5.1; Spring resolves them against a case-insensitive
        // map), so X-Trace-ID and X-Trace-Id are the same header — fold the key for HEADER only (query/path/cookie
        // names stay case-sensitive). The displayed name in messages still prints the raw casing.
        if (p.location() == ParamLocation.HEADER) {
            name = name.toLowerCase(Locale.ROOT);
        }
        return loc + ":" + name;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Drop exact-duplicate findings (same findingId) while preserving order — so one locus isn't double-counted. */
    private List<Finding> dedup(List<Finding> in) {
        Map<String, Finding> byId = new LinkedHashMap<>();
        for (Finding f : in) {
            byId.putIfAbsent(f.getFindingId(), f);
        }
        return new ArrayList<>(byId.values());
    }

    /** Spec A vs spec B (e.g. repo YAML vs Confluence YAML) → SPEC_DRIFT on endpoint-set differences. */
    public List<Finding> diffSpecVsSpec(ApiModel a, ApiModel b) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Endpoint> aByKey = indexByKey(a);
        Map<String, Endpoint> bByKey = indexByKey(b);
        String pair = a.source() + " vs " + b.source();
        for (Endpoint ae : a.endpoints()) {
            if (!bByKey.containsKey(key(ae))) {
                findings.add(finding(FindingType.SPEC_DRIFT, ae.signature(), pair,
                        ae.signature() + " is in " + a.source() + " but not in " + b.source(), null, Confidence.HIGH));
            }
        }
        for (Endpoint be : b.endpoints()) {
            if (!aByKey.containsKey(key(be))) {
                findings.add(finding(FindingType.SPEC_DRIFT, be.signature(), pair,
                        be.signature() + " is in " + b.source() + " but not in " + a.source(), null, Confidence.HIGH));
            }
        }
        return findings;
    }

    /** Parser messages → L1 structural findings. */
    public List<Finding> l1FromMessages(String specSource, List<String> messages) {
        List<Finding> findings = new ArrayList<>();
        if (messages == null) {
            return findings;
        }
        for (String msg : messages) {
            FindingType type = msg != null && msg.toLowerCase(Locale.ROOT).contains("ref")
                    ? FindingType.UNRESOLVED_REF : FindingType.OPENAPI_PARSE_ERROR;
            findings.add(finding(type, null, specSource, msg, null, Confidence.HIGH));
        }
        return findings;
    }

    // ---- matched endpoint comparison ----

    private void compareMatched(List<Finding> findings, ApiModel code, ApiModel spec, Endpoint ce, Endpoint se) {
        // path variable names
        List<String> codeVars = pathVars(ce.pathTemplate());
        List<String> specVars = pathVars(se.pathTemplate());
        if (!codeVars.equals(specVars) && codeVars.size() == specVars.size()) {
            findings.add(finding(FindingType.PATH_VAR_NAME_MISMATCH, label(ce), spec.source(),
                    "Path variable names differ — code " + codeVars + " vs spec " + specVars, ce, Confidence.HIGH));
        }
        // params — keyed by location+name so a code query 'id' never matches a spec path 'id' (different params)
        Map<String, ParamModel> specParams = new LinkedHashMap<>();
        se.params().forEach(p -> specParams.put(paramKey(p), p));
        Map<String, ParamModel> codeParams = new LinkedHashMap<>();
        ce.params().forEach(p -> codeParams.put(paramKey(p), p));
        for (ParamModel cp : ce.params()) {
            if (cp.location() == ParamLocation.PATH) {
                continue; // path vars covered by PATH_VAR_NAME_MISMATCH + the path itself
            }
            ParamModel sp = specParams.get(paramKey(cp));
            if (sp == null) {
                findings.add(finding(FindingType.PARAM_MISSING, label(ce), spec.source(),
                        "Parameter '" + cp.name() + "' (" + cp.location() + ") is in code but missing from the spec",
                        ce, Confidence.HIGH));
                continue;
            }
            if (cp.type() != null && sp.type() != null && !cp.type().equals(sp.type())) {
                findings.add(finding(FindingType.PARAM_TYPE_MISMATCH, label(ce), spec.source(),
                        "Parameter '" + cp.name() + "' type — code " + cp.type() + " vs spec " + sp.type(),
                        ce, Confidence.MEDIUM));
            } else if (cp.type() != null && sp.type() == null) {
                // The spec declares the parameter but omits its type — under-specification, not a clean match.
                findings.add(finding(FindingType.PARAM_TYPE_MISMATCH, label(ce), spec.source(),
                        "Parameter '" + cp.name() + "' type — code declares " + cp.type()
                                + " but the spec omits a type (under-specified)", ce, Confidence.LOW));
            }
            if (cp.required() != sp.required()) {
                findings.add(finding(FindingType.PARAM_REQUIRED_MISMATCH, label(ce), spec.source(),
                        "Parameter '" + cp.name() + "' required — code " + cp.required() + " vs spec " + sp.required(),
                        ce, Confidence.MEDIUM));
            }
            // format divergence (int32 vs int64, date vs date-time, …) — a genuine wire-shape difference type alone
            // misses. LOW confidence and only when BOTH sides declare a format (spec omitting one is under-spec, not drift).
            if (cp.format() != null && sp.format() != null && !cp.format().equals(sp.format())) {
                findings.add(finding(FindingType.CONSTRAINT_GAP, label(ce), spec.source(),
                        "Parameter '" + cp.name() + "' format — code " + cp.format() + " vs spec " + sp.format(),
                        ce, Confidence.LOW));
            }
            if (!cp.constraints().isEmpty()) {
                if (sp.constraints().isEmpty()) {
                    findings.add(finding(FindingType.CONSTRAINT_GAP, label(ce), spec.source(),
                            "Parameter '" + cp.name() + "' has code constraints not exposed in the spec", ce, Confidence.MEDIUM));
                } else {
                    String diff = constraintMismatchDesc(cp.constraints(), sp.constraints());
                    if (diff != null) {
                        findings.add(finding(FindingType.CONSTRAINT_GAP, label(ce), spec.source(),
                                "Parameter '" + cp.name() + "' constraint mismatch — " + diff, ce, Confidence.MEDIUM));
                    }
                }
            } else if (hasEnum(sp.constraints())) {
                // Mirror of the code-enum-vs-spec-string case above: the spec restricts this parameter to an
                // allowed-value SET (enum) that the code's boundary binding does NOT enforce — e.g. it's bound as a
                // plain String/header and validated downstream (a service/converter the static extractor can't see).
                // Surface it so the documented allowed values are verified at the edge, but at LOW confidence because
                // that downstream enforcement is legitimate and out of view. Scoped to enums to stay high-signal
                // (not every format/length hint a controller binds loosely).
                // Word the finding for what the spec ACTUALLY declares: a formal schema enum truly restricts the
                // value set, whereas values parsed from the description prose are documented, not formally enforced.
                String enumMsg = sp.constraints().enumFromDescription()
                        ? "Parameter '" + cp.name() + "' — the spec's description documents the allowed values "
                                + sp.constraints().enumValues() + ", but the schema doesn't formally constrain them and "
                                + "the code doesn't enforce them at the API boundary"
                        : "Parameter '" + cp.name() + "' — the spec restricts it to " + sp.constraints().enumValues()
                                + " but the code binds it without that enum (allowed values not enforced at the API boundary)";
                findings.add(finding(FindingType.CONSTRAINT_GAP, label(ce), spec.source(), enumMsg, ce, Confidence.LOW));
            }
        }
        for (ParamModel sp : se.params()) {
            if (sp.location() == ParamLocation.PATH) {
                continue;
            }
            if (!codeParams.containsKey(paramKey(sp))) {
                findings.add(finding(FindingType.PARAM_EXTRA, label(ce), spec.source(),
                        "Parameter '" + sp.name() + "' (" + sp.location() + ") is in the spec but not in code",
                        ce, Confidence.MEDIUM));
            }
        }
        // request body presence
        boolean codeBody = ce.requestBody() != null;
        boolean specBody = se.requestBody() != null;
        if (codeBody != specBody) {
            findings.add(finding(FindingType.REQUEST_BODY_PRESENCE_MISMATCH, label(ce), spec.source(),
                    "Request body presence — code " + codeBody + " vs spec " + specBody, ce, Confidence.HIGH));
        } else if (codeBody) {
            // Request-body field-level diff (mirror the response path): when BOTH sides declare a body schema, compare
            // the resolved structure so a renamed/extra/missing/type-changed body field surfaces — the presence-only
            // check above missed all of it, leaving request payloads with zero field-level validation.
            String codeBodyRef = ce.requestBody().schemaRef();
            String specBodyRef = se.requestBody().schemaRef();
            if (codeBodyRef != null && specBodyRef != null) {
                int before = findings.size();
                fieldDiffByBinding(findings, code, spec, codeBodyRef, specBodyRef, label(ce) + " request body",
                        new HashSet<>(), MAX_SCHEMA_DEPTH);
                // Coarse fallback mirroring the response path: an array-vs-object (or otherwise diverging) BODY shape is
                // a hard consumer break (POST a JSON array to an object endpoint → 400). fieldDiffByBinding returns
                // early on an array-vs-object top-level body, so without this the cardinality mismatch was dropped.
                boolean bodyFieldEmitted = findings.size() > before;
                if (!bodyFieldEmitted
                        && (arrayRef(codeBodyRef) != arrayRef(specBodyRef) || !normRef(codeBodyRef).equals(normRef(specBodyRef)))
                        && structuralVerdict(code, spec, codeBodyRef, specBodyRef) == SchemaVerdict.DIFFER) {
                    findings.add(finding(FindingType.REQUEST_BODY_SCHEMA_MISMATCH, label(ce), spec.source(),
                            "Request body shape — code expects '" + codeBodyRef + "' but the spec declares '"
                                    + specBodyRef + "'", ce, Confidence.HIGH));
                }
            }
        }
        // success status code — code returns it but spec omits it
        Integer codeStatus = successStatus(ce);
        if (codeStatus != null) {
            boolean present = se.responses().stream().anyMatch(r -> r.statusCode() == codeStatus);
            if (!present) {
                findings.add(finding(FindingType.STATUS_CODE_MISSING, label(ce), spec.source(),
                        "Code returns " + codeStatus + " but the spec doesn't document it", ce, Confidence.MEDIUM));
            }
        }
        // error status codes the code can produce (an error it returns directly, or a mapped @ExceptionHandler
        // advice) that the spec doesn't declare. Confidence tracks reachability: a status the endpoint returns or a
        // specific-exception handler is MEDIUM; a global catch-all handler (e.g. 500 for any RuntimeException) is LOW
        // so it never reads as a hard per-endpoint defect on every operation.
        for (ResponseModel cr : ce.responses()) {
            if (cr.statusCode() < 400) {
                continue;
            }
            ResponseModel specErr = se.responses().stream()
                    .filter(r -> r.statusCode() == cr.statusCode()).findFirst().orElse(null);
            if (specErr != null) {
                // The spec documents this error status — but does the media type agree? An advice that emits
                // application/problem+json against a spec error declared as application/json is a real content drift.
                if (cr.mediaTypes() != null && !cr.mediaTypes().isEmpty()
                        && specErr.mediaTypes() != null && !specErr.mediaTypes().isEmpty()
                        && !mediaSet(cr.mediaTypes()).equals(mediaSet(specErr.mediaTypes()))) {
                    boolean advice = cr.origin() != null && cr.origin().startsWith("EXCEPTION_HANDLER");
                    findings.add(finding(FindingType.CONSUMES_PRODUCES_MISMATCH, label(ce), spec.source(),
                            "Error " + cr.statusCode() + " media type — code " + cr.mediaTypes() + " vs spec "
                                    + specErr.mediaTypes(), ce, advice ? Confidence.LOW : Confidence.MEDIUM));
                }
                continue;
            }
            // Advice-sourced statuses are GLOBAL by construction — a @ControllerAdvice handler is attached to every
            // endpoint regardless of whether that endpoint can actually throw the exception. We can't prove per-endpoint
            // reachability statically, so any advice-sourced status stays LOW (surfaced as manual review, never counted)
            // — otherwise one advice for a broadly-thrown exception would flood every endpoint and tank the score.
            // Only a status the endpoint RETURNS directly (ResponseEntity.badRequest() etc.) is MEDIUM.
            // A status proven reachable via a one-hop service call, or returned by the endpoint directly, is MEDIUM
            // (scored). A blanket @ControllerAdvice status whose reachability we can't prove stays LOW (manual review).
            boolean reachable = "EXCEPTION_HANDLER_REACHABLE".equals(cr.origin());
            boolean blanketAdvice = !reachable && cr.origin() != null && cr.origin().startsWith("EXCEPTION_HANDLER");
            Confidence conf = blanketAdvice ? Confidence.LOW : Confidence.MEDIUM;
            findings.add(finding(FindingType.STATUS_CODE_MISSING, label(ce), spec.source(),
                    "Code can return " + cr.statusCode() + " but the spec doesn't document it", ce, conf));
        }
        // spec documents a 2xx success code the code never returns (success codes only — a spec error code the code
        // doesn't appear to produce is often a deliberately documented contingency, so flagging it would be noise)
        for (ResponseModel sr : se.responses()) {
            if (sr.statusCode() >= 200 && sr.statusCode() < 300
                    && ce.responses().stream().noneMatch(r -> r.statusCode() == sr.statusCode())) {
                findings.add(finding(FindingType.STATUS_CODE_EXTRA, label(ce), spec.source(),
                        "Spec documents success status " + sr.statusCode() + " but the code never returns it",
                        ce, Confidence.LOW));
            }
        }
        // security: code enforces auth (@PreAuthorize/@Secured/...) but the spec declares none (or vice versa)
        boolean codeSecured = ce.security() != null && !ce.security().isEmpty();
        boolean specSecured = se.security() != null && !se.security().isEmpty();
        if (codeSecured && !specSecured) {
            findings.add(finding(FindingType.SECURITY_MISMATCH, label(ce), spec.source(),
                    "Code enforces authorization (" + String.join(", ", ce.security())
                            + ") but the spec declares no security for this operation", ce, Confidence.HIGH));
        } else if (!codeSecured && specSecured && !centralizesSecurity(code)) {
            // Only fire when code authz is annotation-based and genuinely absent. If the project centralizes
            // authorization in a SecurityFilterChain/HttpSecurity bean (invisible to annotation analysis), we
            // cannot conclude the endpoint is unsecured — suppress to avoid a false UNSECURED on every endpoint.
            findings.add(finding(FindingType.SECURITY_MISMATCH, label(ce), spec.source(),
                    "Spec requires security (" + String.join(", ", se.security())
                            + ") but the code enforces none on this endpoint", ce, Confidence.MEDIUM));
        }
        // consumes/produces media-type divergence. Only when the CODE side declares them (most endpoints default
        // to JSON and declare nothing — comparing those would be noise). Compatibility-aware (wildcards/+suffix).
        // A code `produces` media type the spec documents on an ERROR response (e.g. application/problem+json on a 500)
        // is benign — the spec `produces` set is success-only by design, so it must not count as a mismatch.
        Set<String> specErrorMedia = se.responses().stream()
                .filter(r -> r.statusCode() >= 300)
                .flatMap(r -> r.mediaTypes() == null ? Stream.<String>empty() : r.mediaTypes().stream())
                .map(DiffEngine::baseMedia).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        mediaTypeMismatch(findings, ce, spec, "produces", ce.produces(), se.produces(), specErrorMedia);
        mediaTypeMismatch(findings, ce, spec, "consumes", ce.consumes(), se.consumes(), Set.of());
        // Request-body content type (e.g. a multipart/form-data upload whose @RequestPart media type lives on the
        // requestBody, not consumes) — ONLY when the code declared no `consumes` (else the consumes check above already
        // covered it; running both double-counts the same defect since both derive from the same spec content map).
        if ((ce.consumes() == null || ce.consumes().isEmpty()) && ce.requestBody() != null && se.requestBody() != null) {
            mediaTypeMismatch(findings, ce, spec, "request body content",
                    ce.requestBody().mediaTypes(), se.requestBody().mediaTypes(), Set.of());
        }

        // success-response body schema differs between code and spec. Compare the RESOLVED STRUCTURE, not the
        // type/schema NAME: a code DTO "PasswordPolicyWrapper" and a spec schema "policies" that serialize to the
        // same property shape are NOT a contract break — the schema name never appears on the wire.
        String codeRef = successSchemaRef(ce);
        String specRef = successSchemaRef(se);

        // Binding-driven field diff FIRST: when the response schemas bind to the same response but carry DIFFERENT
        // component names (code DTO "PasswordComplexity" vs spec "policies-password-complexity"), the same-name schema
        // loop never field-compares them. Walk the bound pair (pairing nested DTOs by the binding FIELD, not by name)
        // so an undocumented/diverging response field surfaces as a precise SCHEMA_FIELD_MISSING/TYPE_MISMATCH.
        boolean fieldLevelEmitted = false;
        if (codeRef != null && specRef != null) {
            int before = findings.size();
            fieldDiffByBinding(findings, code, spec, codeRef, specRef, label(ce) + " response",
                    new HashSet<>(), MAX_SCHEMA_DEPTH);
            fieldLevelEmitted = findings.size() > before;
        }
        // Emit the COARSE RESPONSE_SCHEMA_MISMATCH only when the precise field-level diff did NOT already describe the
        // same response divergence — otherwise one schema defect would be penalised twice (the coarse mismatch AND its
        // per-field findings), depressing the FidelityScore for a single underlying defect. The coarse finding is still
        // the only signal for array-vs-object (fieldDiffByBinding returns early there) and is retained for it.
        if (codeRef != null && specRef != null && !fieldLevelEmitted
                && (arrayRef(codeRef) != arrayRef(specRef) || !normRef(codeRef).equals(normRef(specRef)))
                && structuralVerdict(code, spec, codeRef, specRef) == SchemaVerdict.DIFFER) {
            findings.add(finding(FindingType.RESPONSE_SCHEMA_MISMATCH, label(ce), spec.source(),
                    "Success response schema — code returns '" + codeRef + "' but the spec declares '" + specRef + "'",
                    ce, Confidence.MEDIUM));
        }
    }

    private void fieldDiffByBinding(List<Finding> findings, ApiModel code, ApiModel spec, String codeRef, String specRef,
                                    String locus, Set<String> visited, int depth) {
        if (arrayRef(codeRef) != arrayRef(specRef)) {
            return;   // array-vs-object is the structuralVerdict's call, not a field diff
        }
        SchemaModel cs = code.schemas().get(baseName(codeRef));
        SchemaModel ss = spec.schemas().get(baseName(specRef));
        if (cs == null || ss == null || structureless(cs) || suppressStructurelessSpec(spec, ss)) {
            return;   // unresolved / opaque — owned by structuralVerdict + extractor blind spots
        }
        String key = baseName(codeRef) + "|" + baseName(specRef);
        if (!visited.add(key)) {
            return;   // cycle guard
        }
        try {
            // Same-name pairs are already field-diffed by the components-schema loop (dedup collapses any overlap),
            // so only run the diff here for the differently-named bound pairs that loop never reaches.
            if (!baseName(codeRef).equalsIgnoreCase(baseName(specRef))) {
                compareSchema(findings, spec.source(), locus, cs, ss);
            }
            if (depth <= 0) {
                return;
            }
            Map<String, FieldModel> cf = fieldsByName(cs);
            Map<String, FieldModel> sf = fieldsByName(ss);
            for (Map.Entry<String, FieldModel> e : cf.entrySet()) {
                FieldModel c = e.getValue();
                FieldModel s = sf.get(e.getKey());
                if (s == null || c.refSchema() == null || s.refSchema() == null) {
                    continue;   // pair nested DTOs only where the binding field exists on both sides
                }
                if (arrayRef(c.refSchema()) != arrayRef(s.refSchema())) {
                    // A nested field that is an array on one side and a single object on the other — a real wire-shape
                    // break the recursion would otherwise drop (fieldDiffByBinding returns early on array-vs-object).
                    findings.add(finding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, locus + "." + e.getKey(), spec.source(),
                            "Field '" + e.getKey() + "' of " + locus + " is "
                                    + (arrayRef(c.refSchema()) ? "an array in code but a single object in the spec"
                                                               : "a single object in code but an array in the spec"),
                            null, Confidence.HIGH));
                    continue;
                }
                fieldDiffByBinding(findings, code, spec, c.refSchema(), s.refSchema(),
                        locus + "." + e.getKey(), visited, depth - 1);
            }
        } finally {
            visited.remove(key);
        }
    }

    private enum SchemaVerdict { MATCH, DIFFER, UNRESOLVED }

    /** Bound on nested-schema recursion when comparing structure (cycles are also guarded by a visited set). */
    private static final int MAX_SCHEMA_DEPTH = 8;

    /**
     * Decide whether two differently-named success-response schemas are a genuine contract break by comparing their
     * RESOLVED property structure rather than their names. Returns {@code DIFFER} only when the structures truly
     * diverge; {@code UNRESOLVED} when either side can't be looked up or carries no structure to compare (a
     * name-compare there is exactly the false positive we are removing — such external/opaque DTOs are already
     * recorded as extractor blind spots).
     */
    private SchemaVerdict structuralVerdict(ApiModel code, ApiModel spec, String codeRef, String specRef) {
        if (arrayRef(codeRef) != arrayRef(specRef)) {
            return SchemaVerdict.DIFFER;   // array vs single object is a real shape difference
        }
        SchemaModel cs = code.schemas().get(baseName(codeRef));
        SchemaModel ss = spec.schemas().get(baseName(specRef));
        // A KNOWN scalar (String/Integer/...) on exactly one side, against a structured object on the other, is a
        // PROVABLE shape break — a bare JSON string can never equal an object. Don't fold it into UNRESOLVED (which is
        // reserved for genuinely opaque/external DTOs and would suppress the mismatch).
        // A side counts as scalar ONLY when its name is a known scalar AND it is NOT a registered structured schema —
        // otherwise a code/spec DTO that happens to be named "Instant"/"Date"/"Number" would falsely read as a scalar
        // and a structurally-equal object pair would DIFFER on the name alone.
        boolean codeScalar = isScalarName(baseName(codeRef)) && cs == null;
        boolean specScalar = isScalarName(baseName(specRef)) && ss == null;
        if (codeScalar != specScalar) {
            SchemaModel object = codeScalar ? ss : cs;
            if (object != null && !structureless(object)) {
                return SchemaVerdict.DIFFER;
            }
        }
        if (cs == null || ss == null || structureless(cs) || suppressStructurelessSpec(spec, ss)) {
            return SchemaVerdict.UNRESOLVED;
        }
        return propsEqual(code, spec, cs, ss, MAX_SCHEMA_DEPTH, new HashSet<>())
                ? SchemaVerdict.MATCH : SchemaVerdict.DIFFER;
    }

    private static boolean arrayRef(String ref) {
        return ref != null && ref.endsWith("[]");
    }

    /** Known scalar/primitive ref names (Java type names + OpenAPI scalar types) — used to tell a provable
     *  scalar-vs-object response break apart from a genuinely-unresolvable external DTO. */
    private static final Set<String> SCALAR_REF_NAMES = Set.of(
            "string", "integer", "int", "long", "short", "byte", "boolean", "double", "float", "number",
            "bigdecimal", "biginteger", "character", "char", "uuid", "date", "localdate", "localdatetime",
            "instant", "offsetdatetime", "zoneddatetime", "void");

    private static boolean isScalarName(String ref) {
        return ref != null && SCALAR_REF_NAMES.contains(ref.toLowerCase(Locale.ROOT));
    }

    private static String baseName(String ref) {
        return ref == null ? null : ref.replace("[]", "");
    }

    /** A schema we cannot structurally compare: no extracted fields and no enum values. */
    private static boolean structureless(SchemaModel s) {
        boolean noFields = s.fields() == null || s.fields().isEmpty();
        boolean noEnum = s.enumValues() == null || s.enumValues().isEmpty();
        return noFields && noEnum;
    }

    /** Whether to suppress (treat as UNRESOLVED) a structureless SPEC schema. Suppress by default — the ONLY case the
     *  code's fields should surface as SCHEMA_FIELD_MISSING is a GENUINELY-EMPTY DECLARED object: type:object, no
     *  properties, and no composition blind spot (i.e. under-documentation). A bare-$ref alias / external $ref (type
     *  != "object") or a composition-opaque schema (oneOf/anyOf/unresolvable allOf, blind-spotted) stays suppressed,
     *  so neither produces a false SCHEMA_FIELD_MISSING. */
    private static boolean suppressStructurelessSpec(ApiModel spec, SchemaModel ss) {
        if (!structureless(ss)) {
            return false;
        }
        boolean genuinelyEmptyObject = "object".equals(ss.type()) && !specSchemaComposed(spec, ss);
        return !genuinelyEmptyObject;
    }

    /** True when the extractor recorded a composition blind spot for a spec schema (oneOf/anyOf/unresolvable allOf). */
    private static boolean specSchemaComposed(ApiModel spec, SchemaModel ss) {
        if (spec.blindSpots() == null || ss == null || ss.name() == null) {
            return false;
        }
        String marker = "'" + ss.name() + "'";
        return spec.blindSpots().stream()
                .anyMatch(b -> b != null && b.contains(marker) && b.contains("composition"));
    }

    /** Structural equality: enum value sets, or same field-name set with compatible field types, recursing into nested
     * $ref'd schemas up to {@link #MAX_SCHEMA_DEPTH} (a visited-pair set guards cyclic DTO graphs). */
    private boolean propsEqual(ApiModel code, ApiModel spec, SchemaModel cs, SchemaModel ss, int depth,
                               Set<String> visited) {
        String key = cs.name() + "|" + ss.name();
        if (!visited.add(key)) {
            return true;   // this pair is already being compared higher up the stack — break the cycle
        }
        // Scope the guard to genuine stack ANCESTORS: removing on exit means a pair truncated by depth on one path
        // can still be fully compared when reached via a shorter path, so a deep diff isn't wrongly memoized as MATCH.
        try {
            boolean cEnum = cs.enumValues() != null && !cs.enumValues().isEmpty();
            boolean sEnum = ss.enumValues() != null && !ss.enumValues().isEmpty();
            if (cEnum || sEnum) {
                return cEnum && sEnum && normSet(cs.enumValues()).equals(normSet(ss.enumValues()));
            }
            Map<String, FieldModel> cf = fieldsByName(cs);
            Map<String, FieldModel> sf = fieldsByName(ss);
            if (!cf.keySet().equals(sf.keySet())) {
                return false;
            }
            for (Map.Entry<String, FieldModel> e : cf.entrySet()) {
                FieldModel c = e.getValue();
                FieldModel s = sf.get(e.getKey());
                if (!typeCompatible(c, s)) {
                    return false;
                }
                if (depth > 0 && c.refSchema() != null && s.refSchema() != null) {
                    if (arrayRef(c.refSchema()) != arrayRef(s.refSchema())) {
                        return false;
                    }
                    SchemaModel nc = code.schemas().get(baseName(c.refSchema()));
                    SchemaModel ns = spec.schemas().get(baseName(s.refSchema()));
                    // recurse only when both nested schemas resolve; an unresolved nested side never invents a diff
                    if (nc != null && ns != null && !propsEqual(code, spec, nc, ns, depth - 1, visited)) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            visited.remove(key);
        }
    }

    private static Map<String, FieldModel> fieldsByName(SchemaModel s) {
        Map<String, FieldModel> m = new LinkedHashMap<>();
        if (s.fields() != null) {
            s.fields().forEach(f -> m.put(f.jsonName(), f));
        }
        return m;
    }

    /** Field types are compatible when equal, or when either side is null/object (same wildcard rule as compareSchema). */
    private static boolean typeCompatible(FieldModel a, FieldModel b) {
        if (a.type() == null || b.type() == null || "object".equals(a.type()) || "object".equals(b.type())) {
            return true;
        }
        return a.type().equals(b.type());
    }

    private String successSchemaRef(Endpoint e) {
        return e.responses().stream()
                .filter(r -> r.statusCode() >= 200 && r.statusCode() < 300)
                .map(ResponseModel::schemaRef)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    private String normRef(String ref) {
        return ref == null ? null : ref.replace("[]", "").toLowerCase(Locale.ROOT);
    }

    /** Flag a consumes/produces divergence only when the code side declares media types (else it's noise). */
    private void mediaTypeMismatch(List<Finding> findings, Endpoint ce, ApiModel spec, String which,
                                   List<String> code, List<String> specMt,
                                   Set<String> benignExtra) {
        if (code == null || code.isEmpty() || specMt == null || specMt.isEmpty()) {
            return;
        }
        List<String> effCode = code.stream()
                .filter(c -> !benignExtra.contains(baseMedia(c))).toList();
        if (effCode.isEmpty() || mediaCompatible(effCode, specMt)) {
            return;
        }
        findings.add(finding(FindingType.CONSUMES_PRODUCES_MISMATCH, label(ce), spec.source(),
                which + " media types — code " + code + " vs spec " + specMt, ce, Confidence.LOW));
    }

    /** Media-type compatibility per Spring's MediaType semantics (a star/star or application-star wildcard, and an
     *  application-star-plus-json range matching application/json) — replaces literal set-equality so a wildcard or
     *  +suffix range is not a false CONSUMES_PRODUCES_MISMATCH. Falls back to base-string equality on a parse failure. */
    private boolean mediaCompatible(List<String> code, List<String> specMt) {
        try {
            List<MediaType> cs = parseMedia(code);
            List<MediaType> ss = parseMedia(specMt);
            if (cs.isEmpty() || ss.isEmpty()) {
                return mediaSet(code).equals(mediaSet(specMt));
            }
            return cs.stream().allMatch(c -> ss.stream().anyMatch(s -> s.isCompatibleWith(c)))
                    && ss.stream().allMatch(s -> cs.stream().anyMatch(c -> c.isCompatibleWith(s)));
        } catch (RuntimeException unparseable) {
            return mediaSet(code).equals(mediaSet(specMt));
        }
    }

    private List<MediaType> parseMedia(List<String> v) {
        List<MediaType> out = new ArrayList<>();
        for (String x : v) {
            if (x != null && !x.isBlank()) {
                out.add(MediaType.parseMediaType(x.trim()));
            }
        }
        return out;
    }

    /** Lower-cased base media type, parameters stripped: {@code Application/JSON;charset=UTF-8} → {@code application/json}. */
    private static String baseMedia(String x) {
        if (x == null) {
            return null;
        }
        String b = x.toLowerCase(Locale.ROOT);
        int semi = b.indexOf(';');
        b = (semi >= 0 ? b.substring(0, semi) : b).trim();
        return b.isEmpty() ? null : b;
    }

    /** Media types compared by base type, case-insensitive, ignoring parameters (e.g. {@code ;charset=utf-8}). */
    private Set<String> mediaSet(List<String> v) {
        Set<String> out = new HashSet<>();
        for (String x : v) {
            String base = baseMedia(x);
            if (base != null) {
                out.add(base);
            }
        }
        return out;
    }

    /**
     * True when part of the code's routing could not be statically analysed, so a spec endpoint "not found in code" is
     * UNVERIFIABLE rather than provably dead: controllers delegating their @*Mapping to interfaces outside the scanned
     * sources (openapi-generator API interfaces), functional {@code RouterFunction} routing, or Kotlin sources this
     * Java/annotation extractor cannot read.
     */
    private boolean codeExtractionIncomplete(ApiModel code) {
        if (code.blindSpots() == null) {
            return false;
        }
        return code.blindSpots().stream().anyMatch(b -> b != null && (
                b.contains("mappings declared on interfaces are not analysed")
                        || b.contains("request mappings it declares are not analysed")   // extends an unscanned base
                        || b.contains("RouterFunction")
                        || b.contains("Functional routing")
                        || b.contains("Kotlin source file(s) declare Spring web routing")
                        || b.contains("coRouter")));
    }

    /** True when the code model flagged that authorization is centralized in a SecurityFilterChain/HttpSecurity bean. */
    private boolean centralizesSecurity(ApiModel code) {
        return code.blindSpots() != null && code.blindSpots().stream()
                .anyMatch(b -> b != null && (b.contains("SecurityFilterChain") || b.contains("HttpSecurity")));
    }

    /** First constraint keyword whose value differs between two non-empty sets, or null if equivalent. */
    /** True if the constraint set declares a non-empty enum (allowed-value set). */
    private static boolean hasEnum(ConstraintSet cs) {
        return cs != null && cs.enumValues() != null && !cs.enumValues().isEmpty();
    }

    private String constraintMismatchDesc(ConstraintSet c, ConstraintSet s) {
        if (c == null || s == null) {
            return null;
        }
        if (!Objects.equals(c.minLength(), s.minLength())) {
            return "minLength code=" + c.minLength() + " spec=" + s.minLength();
        }
        if (!Objects.equals(c.maxLength(), s.maxLength())) {
            return "maxLength code=" + c.maxLength() + " spec=" + s.maxLength();
        }
        if (!Objects.equals(c.minimum(), s.minimum())) {
            return "minimum code=" + c.minimum() + " spec=" + s.minimum();
        }
        if (!Objects.equals(c.maximum(), s.maximum())) {
            return "maximum code=" + c.maximum() + " spec=" + s.maximum();
        }
        // exclusiveMinimum/Maximum: null and false both mean "inclusive", so compare on TRUE-ness only (else a code
        // null vs a spec explicit-false would false-diff).
        if (Boolean.TRUE.equals(c.exclusiveMin()) != Boolean.TRUE.equals(s.exclusiveMin())) {
            return "exclusiveMinimum code=" + c.exclusiveMin() + " spec=" + s.exclusiveMin();
        }
        if (Boolean.TRUE.equals(c.exclusiveMax()) != Boolean.TRUE.equals(s.exclusiveMax())) {
            return "exclusiveMaximum code=" + c.exclusiveMax() + " spec=" + s.exclusiveMax();
        }
        if (!Objects.equals(c.pattern(), s.pattern())) {
            return "pattern code=" + c.pattern() + " spec=" + s.pattern();
        }
        if (!sameValueSet(c.enumValues(), s.enumValues())) {
            return "enum code=" + c.enumValues() + " spec=" + s.enumValues();
        }
        return null;
    }

    /** Enum equivalence is by VALUE SET, case-insensitive — declaration order and casing differences are not drift. */
    private boolean sameValueSet(List<String> a, List<String> b) {
        return normSet(a).equals(normSet(b));
    }

    private Set<String> normSet(List<String> v) {
        Set<String> out = new HashSet<>();
        if (v != null) {
            for (String x : v) {
                if (x != null) {
                    out.add(x.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private void compareSchema(List<Finding> findings, String specSource, String name, SchemaModel codeS, SchemaModel specS) {
        Map<String, FieldModel> specFields = new LinkedHashMap<>();
        specS.fields().forEach(f -> specFields.put(f.jsonName(), f));
        Map<String, FieldModel> codeFields = new LinkedHashMap<>();
        codeS.fields().forEach(f -> codeFields.put(f.jsonName(), f));
        for (FieldModel cf : codeS.fields()) {
            FieldModel sf = specFields.get(cf.jsonName());
            if (sf == null) {
                findings.add(finding(FindingType.SCHEMA_FIELD_MISSING, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' of " + name + " is in code but missing from the spec schema",
                        null, Confidence.HIGH));
                continue;
            }
            if (cf.type() != null && sf.type() != null && !cf.type().equals(sf.type())
                    && !"object".equals(cf.type()) && !"object".equals(sf.type())) {
                findings.add(finding(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' type — code " + cf.type() + " vs spec " + sf.type(),
                        null, Confidence.MEDIUM));
            }
            // required drift — ONLY the faithful direction: the code POSITIVELY asserts the field is required
            // (@NotNull/@NotBlank/@NotEmpty) but the spec marks it optional. The reverse (code false, spec required)
            // is NOT reliable drift — code-side `required=false` means "no bean-validation annotation here", not
            // "optional" (the field may be validated in the service/constructor), so flagging it would be a false
            // positive on every conforming spec-required field that the code doesn't annotate (esp. response DTOs).
            if (cf.required() && !sf.required()) {
                findings.add(finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' is required in code but optional in the spec",
                        null, Confidence.MEDIUM));
            }
            // format divergence (date vs date-time, int32 vs int64, …) — only when both declare a format.
            if (cf.format() != null && sf.format() != null && !cf.format().equals(sf.format())) {
                findings.add(finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                        "Field '" + cf.jsonName() + "' format — code " + cf.format() + " vs spec " + sf.format(),
                        null, Confidence.LOW));
            }
            if (!cf.constraints().isEmpty()) {
                if (sf.constraints().isEmpty()) {
                    findings.add(finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                            "Field '" + cf.jsonName() + "' has code constraints not exposed in the spec", null, Confidence.MEDIUM));
                } else {
                    String diff = constraintMismatchDesc(cf.constraints(), sf.constraints());
                    if (diff != null) {
                        findings.add(finding(FindingType.CONSTRAINT_GAP, name + "." + cf.jsonName(), specSource,
                                "Field '" + cf.jsonName() + "' constraint mismatch — " + diff, null, Confidence.MEDIUM));
                    }
                }
            }
        }
        for (FieldModel sf : specS.fields()) {
            if (!codeFields.containsKey(sf.jsonName())) {
                findings.add(finding(FindingType.SCHEMA_FIELD_EXTRA, name + "." + sf.jsonName(), specSource,
                        "Field '" + sf.jsonName() + "' of " + name + " is in the spec but not in code", null, Confidence.LOW));
            }
        }
    }

    // ---- helpers ----

    private Finding finding(FindingType type, String endpoint, String specSource, String summary,
                            Endpoint codeEndpoint, Confidence confidence) {
        return Finding.builder()
                .findingId(Integer.toHexString(Objects.hash(type, endpoint, summary, specSource)))
                .type(type)
                .layer(layerOf(type))
                .severity(severityOf(type))
                .confidence(confidence)
                .origin("DETERMINISTIC")
                .endpoint(endpoint)
                .specSource(specSource)
                .summary(summary)
                .codeEvidence(codeEndpoint == null ? null : codeEndpoint.source())
                .build();
    }

    private Layer layerOf(FindingType t) {
        return switch (t) {
            case OPENAPI_PARSE_ERROR, UNRESOLVED_REF, MISSING_INFO_FIELD -> Layer.L1;
            case MISSING_ENDPOINT -> Layer.L2;
            case EXTRA_ENDPOINT, SPEC_DRIFT -> Layer.L3;
            default -> Layer.L4;
        };
    }

    /**
     * Severity by CONSUMER IMPACT, calibrated against API-governance linting (Spectral/Redocly error/warn/info),
     * OpenAPI breaking-change classification (oasdiff / openapi-diff), and OWASP API Security + ISTQB risk:
     * <ul>
     *   <li>BLOCKER  — the spec is invalid/unresolvable, so no generated client can rely on it;</li>
     *   <li>CRITICAL — a definite endpoint-level consumer break, or a security-contract gap (OWASP API1/2/5);</li>
     *   <li>MAJOR    — request/response-shape functional risk (params, status, schema fields/types, constraints);</li>
     *   <li>MINOR    — dead-spec / additive / positional-naming drift that misleads but doesn't break a running client
     *       (a path-variable NAME is positional in the URL, so {@code {app}} vs {@code {appId}} is non-breaking);</li>
     *   <li>INFO     — documentation/advisory only; INFO carries no score penalty.</li>
     * </ul>
     */
    private Severity severityOf(FindingType t) {
        return switch (t) {
            case OPENAPI_PARSE_ERROR, UNRESOLVED_REF -> Severity.BLOCKER;
            case MISSING_ENDPOINT, VERB_MISMATCH, SECURITY_MISMATCH -> Severity.CRITICAL;
            case PARAM_MISSING, PARAM_TYPE_MISMATCH, PARAM_REQUIRED_MISMATCH, REQUEST_BODY_PRESENCE_MISMATCH,
                 REQUEST_BODY_SCHEMA_MISMATCH, STATUS_CODE_MISSING, RESPONSE_SCHEMA_MISMATCH, SCHEMA_FIELD_MISSING,
                 SCHEMA_FIELD_TYPE_MISMATCH, CONSTRAINT_GAP -> Severity.MAJOR;
            case MISSING_INFO_FIELD, DESIGN_QUALITY, TEST_BASIS_GAP -> Severity.INFO;
            // EXTRA_ENDPOINT, PATH_VAR_NAME_MISMATCH, SPEC_DRIFT, PARAM_EXTRA, STATUS_CODE_EXTRA,
            // SCHEMA_FIELD_EXTRA, CONSUMES_PRODUCES_MISMATCH — dead-spec / additive / naming drift, non-breaking
            default -> Severity.MINOR;
        };
    }

    private Map<String, Endpoint> indexByKey(ApiModel m) {
        Map<String, Endpoint> map = new LinkedHashMap<>();
        m.endpoints().forEach(e -> map.put(key(e), e));
        return map;
    }

    private Map<String, Endpoint> indexByPath(ApiModel m) {
        Map<String, Endpoint> map = new LinkedHashMap<>();
        m.endpoints().forEach(e -> map.putIfAbsent(normPath(e.pathTemplate()), e));
        return map;
    }

    /** All HTTP verbs declared on each normalized path — so a verb-mismatch message names the full set, not one. */
    private Map<String, Set<HttpMethod>> verbsByPath(ApiModel m) {
        Map<String, Set<HttpMethod>> map = new LinkedHashMap<>();
        m.endpoints().forEach(e ->
                map.computeIfAbsent(normPath(e.pathTemplate()), k -> new TreeSet<>()).add(e.method()));
        return map;
    }

    private String key(Endpoint e) {
        return e.method() + " " + normPath(e.pathTemplate());
    }

    private String label(Endpoint e) {
        return e.signature();
    }

    static String normPath(String path) {
        if (path == null) {
            return "/";
        }
        // Collapse each path variable to {} with a BRACE-BALANCED scan, so a quantifier brace in a regex var
        // ({id:[0-9]{2}}) is consumed as one token (the old \{[^}]*\} stopped at the first '}' and left a stray '}').
        String p = collapsePathVars(path.toLowerCase(Locale.ROOT)).replaceAll("/+", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "/" : p;
    }

    private static String collapsePathVars(String path) {
        if (path.indexOf('{') < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            int depth = 1;
            while (j < n && depth > 0) {
                char d = path.charAt(j);
                if (d == '{') {
                    depth++;
                } else if (d == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                j++;
            }
            out.append("{}");
            i = j + 1;
        }
        return out.toString();
    }

    private List<String> pathVars(String path) {
        List<String> vars = new ArrayList<>();
        if (path == null) {
            return vars;
        }
        var matcher = java.util.regex.Pattern.compile("\\{([^}]*)}").matcher(path);
        while (matcher.find()) {
            vars.add(matcher.group(1));
        }
        return vars;
    }

    private Integer successStatus(Endpoint e) {
        Optional<ResponseModel> success = e.responses().stream()
                .filter(r -> r.statusCode() >= 200 && r.statusCode() < 300).findFirst();
        return success.map(ResponseModel::statusCode).orElse(null);
    }

    private boolean constraintGap(ConstraintSet code, ConstraintSet spec) {
        return code != null && !code.isEmpty() && (spec == null || spec.isEmpty());
    }
}
