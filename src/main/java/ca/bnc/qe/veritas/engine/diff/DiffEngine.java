package ca.bnc.qe.veritas.engine.diff;

import static ca.bnc.qe.veritas.engine.diff.SchemaComparator.MAX_SCHEMA_DEPTH;
import static ca.bnc.qe.veritas.engine.diff.SchemaComparator.compareSchema;
import static ca.bnc.qe.veritas.engine.diff.SchemaComparator.fieldDiffByBinding;
import static ca.bnc.qe.veritas.engine.diff.SchemaComparator.hasEnum;
import static ca.bnc.qe.veritas.engine.diff.SchemaComparator.normRef;
import static ca.bnc.qe.veritas.engine.diff.SchemaComparator.structuralVerdict;
import static ca.bnc.qe.veritas.engine.diff.SchemaComparator.suppressStructurelessSpec;

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
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.SourceRef;
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

        // schema field-level diff for schemas present (by name) on both sides. Skip a spec schema that is structureless
        // by composition (oneOf/anyOf/unresolvable allOf — the extractor recorded a blind spot for it): field-diffing
        // an opaque union against the code DTO would emit a false SCHEMA_FIELD_MISSING for every code field. This mirrors
        // the differently-named binding path's suppressStructurelessSpec gate (compareMatched/fieldDiffByBinding).
        for (Map.Entry<String, SchemaModel> e : code.schemas().entrySet()) {
            SchemaModel specSchema = spec.schemas().get(e.getKey());
            if (specSchema != null && !suppressStructurelessSpec(spec, specSchema)) {
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
        comparePathVarNames(findings, spec, ce, se);
        compareParams(findings, code, spec, ce, se);
        compareRequestBody(findings, code, spec, ce, se);
        compareStatusCodes(findings, spec, ce, se);
        compareSecurity(findings, code, spec, ce, se);
        compareMediaTypes(findings, spec, ce, se);
        compareResponseSchema(findings, code, spec, ce, se);
    }

    private void comparePathVarNames(List<Finding> findings, ApiModel spec, Endpoint ce, Endpoint se) {
        // path variable names
        List<String> codeVars = pathVars(ce.pathTemplate());
        List<String> specVars = pathVars(se.pathTemplate());
        if (!codeVars.equals(specVars) && codeVars.size() == specVars.size()) {
            // A neutral, deterministic proposed fix: a path variable is positional, so this is a naming convention
            // choice for the API owner — NOT "rename the spec to match the code". Set it here so it pre-empts the
            // reconcile LLM (which only fills a null proposedFix).
            Finding f = finding(FindingType.PATH_VAR_NAME_MISMATCH, label(ce), spec.source(),
                    "Path variable names differ — code " + codeVars + " vs spec " + specVars, ce, Confidence.HIGH);
            findings.add(f.toBuilder().proposedFix(
                    "Path variable names are positional and non-breaking — " + codeVars + " and " + specVars
                    + " route identically. Pick one naming convention with the API owner: align the spec to the code, "
                    + "or keep the spec's more descriptive name and rename the code. A convention choice, not a "
                    + "required fix.").build());
        }
    }

    private void compareParams(List<Finding> findings, ApiModel code, ApiModel spec, Endpoint ce, Endpoint se) {
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
            // "object" is the extractor's honest "unknown/unmapped" marker (a Converter<String,T>-bound value type,
            // char, Locale, MultipartFile, …) — treat it as a wildcard, exactly as compareSchema/typeCompatible do for
            // schema fields. Comparing it against a concrete spec scalar would be a false PARAM_TYPE_MISMATCH.
            boolean typeWildcard = "object".equals(cp.type()) || "object".equals(sp.type());
            if (!typeWildcard && cp.type() != null && sp.type() != null && !cp.type().equals(sp.type())) {
                findings.add(finding(FindingType.PARAM_TYPE_MISMATCH, label(ce), spec.source(),
                        "Parameter '" + cp.name() + "' type — code " + cp.type() + " vs spec " + sp.type(),
                        ce, Confidence.MEDIUM));
            } else if (!typeWildcard && cp.type() != null && sp.type() == null) {
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
                    String diff = ConstraintComparator.mismatchDesc(cp.constraints(), sp.constraints(),
                            ConstraintComparator.isIntegerTyped(cp.type()), ConstraintComparator.isIntegerTyped(sp.type()));
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
        // The code binds query params loosely (a @ModelAttribute/command object, Pageable, @RequestParam Map bind-all,
        // or @MatrixVariable) — the extractor emits ZERO params + a blind spot for these, so every flattened spec query
        // param would otherwise false-diff as PARAM_EXTRA. Suppress that (the extractor's own intent).
        boolean codeFlattensQueryParams = bindsAllQueryParams(code, ce);
        for (ParamModel sp : se.params()) {
            if (sp.location() == ParamLocation.PATH) {
                continue;
            }
            if (codeFlattensQueryParams && (sp.location() == ParamLocation.QUERY || sp.location() == null)) {
                continue;
            }
            if (!codeParams.containsKey(paramKey(sp))) {
                findings.add(finding(FindingType.PARAM_EXTRA, label(ce), spec.source(),
                        "Parameter '" + sp.name() + "' (" + sp.location() + ") is in the spec but not in code",
                        ce, Confidence.MEDIUM));
            }
        }
    }

    private void compareRequestBody(List<Finding> findings, ApiModel code, ApiModel spec, Endpoint ce, Endpoint se) {
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
                        && structuralVerdict(code, spec, codeBodyRef, specBodyRef) == SchemaComparator.SchemaVerdict.DIFFER) {
                    findings.add(finding(FindingType.REQUEST_BODY_SCHEMA_MISMATCH, label(ce), spec.source(),
                            "Request body shape — code expects '" + codeBodyRef + "' but the spec declares '"
                                    + specBodyRef + "'", ce, Confidence.HIGH));
                }
            }
        }
    }

    private void compareStatusCodes(List<Finding> findings, ApiModel spec, Endpoint ce, Endpoint se) {
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
            if (cr.statusCode() < 300) {
                continue;   // 2xx is the success band (handled above); 3xx redirects + 4xx/5xx errors are compared here
            }
            ResponseModel specErr = se.responses().stream()
                    .filter(r -> r.statusCode() == cr.statusCode()).findFirst().orElse(null);
            if (specErr != null) {
                // The spec documents this error status — but does the media type agree? An advice that emits
                // application/problem+json against a spec error declared as application/json is a real content drift.
                if (cr.mediaTypes() != null && !cr.mediaTypes().isEmpty()
                        && specErr.mediaTypes() != null && !specErr.mediaTypes().isEmpty()
                        && !MediaTypeComparator.compatible(cr.mediaTypes(), specErr.mediaTypes())) {
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
            } else if (sr.statusCode() >= 300 && sr.statusCode() < 400
                    && ce.responses().stream().noneMatch(r -> r.statusCode() == sr.statusCode())) {
                // A documented 3xx redirect the code never returns — a real (if often contingent) wire divergence.
                findings.add(finding(FindingType.STATUS_CODE_EXTRA, label(ce), spec.source(),
                        "Spec documents redirect status " + sr.statusCode() + " but the code never returns it",
                        ce, Confidence.LOW));
            }
        }
    }

    private void compareSecurity(List<Finding> findings, ApiModel code, ApiModel spec, Endpoint ce, Endpoint se) {
        // security: code enforces auth (@PreAuthorize/@Secured/...) but the spec declares none (or vice versa)
        boolean codeSecured = ce.security() != null && !ce.security().isEmpty();
        boolean specSecured = se.security() != null && !se.security().isEmpty();
        if (codeSecured && !specSecured && hasResolvedSecurity(ce)) {
            // Only when at least one code-side token is a RESOLVED authorization constraint (a real role, a securing
            // SpEL, denyAll). A code endpoint whose security is ONLY an "unresolved:" suggestive annotation (e.g. a
            // custom @AdminApi we couldn't read, or a swagger doc annotation) is uncertain, not provably secured —
            // the extractor already records a blind spot for manual review; fabricating a CRITICAL here is a false
            // positive (the unresolved token still makes codeSecured true, so the spec-secured branch stays suppressed).
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
    }

    private void compareMediaTypes(List<Finding> findings, ApiModel spec, Endpoint ce, Endpoint se) {
        // consumes/produces media-type divergence. Only when the CODE side declares them (most endpoints default
        // to JSON and declare nothing — comparing those would be noise). Compatibility-aware (wildcards/+suffix).
        // A code `produces` media type the spec documents on an ERROR response (e.g. application/problem+json on a 500)
        // is benign — the spec `produces` set is success-only by design, so it must not count as a mismatch.
        Set<String> specErrorMedia = se.responses().stream()
                .filter(r -> r.statusCode() >= 300)
                .flatMap(r -> r.mediaTypes() == null ? Stream.<String>empty() : r.mediaTypes().stream())
                .map(MediaTypeComparator::base).filter(Objects::nonNull)
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

    }

    private void compareResponseSchema(List<Finding> findings, ApiModel code, ApiModel spec, Endpoint ce, Endpoint se) {
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
                && structuralVerdict(code, spec, codeRef, specRef) == SchemaComparator.SchemaVerdict.DIFFER) {
            findings.add(finding(FindingType.RESPONSE_SCHEMA_MISMATCH, label(ce), spec.source(),
                    "Success response schema — code returns '" + codeRef + "' but the spec declares '" + specRef + "'",
                    ce, Confidence.MEDIUM));
        }
    }

    private String successSchemaRef(Endpoint e) {
        return e.responses().stream()
                .filter(r -> r.statusCode() >= 200 && r.statusCode() < 300)
                .map(ResponseModel::schemaRef)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** Flag a consumes/produces divergence only when the code side declares media types (else it's noise). */
    private void mediaTypeMismatch(List<Finding> findings, Endpoint ce, ApiModel spec, String which,
                                   List<String> code, List<String> specMt,
                                   Set<String> benignExtra) {
        if (code == null || code.isEmpty() || specMt == null || specMt.isEmpty()) {
            return;
        }
        List<String> effCode = code.stream()
                .filter(c -> !benignExtra.contains(MediaTypeComparator.base(c))).toList();
        if (effCode.isEmpty() || MediaTypeComparator.compatible(effCode, specMt)) {
            return;
        }
        findings.add(finding(FindingType.CONSUMES_PRODUCES_MISMATCH, label(ce), spec.source(),
                which + " media types — code " + code + " vs spec " + specMt, ce, Confidence.LOW));
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

    /** True when the code endpoint carries at least one RESOLVED authorization token (a real role, a securing SpEL, or
     *  denyAll) — i.e. not only "unresolved:" suggestive-but-unread annotations. A scored security mismatch needs a
     *  constraint we actually read; an unresolved-only endpoint is surfaced as a blind spot, not a CRITICAL finding. */
    private boolean hasResolvedSecurity(Endpoint ce) {
        return ce.security() != null && ce.security().stream()
                .anyMatch(t -> t != null && !t.startsWith("unresolved:"));
    }

    /** True when the code model flagged that authorization is centralized in a SecurityFilterChain/HttpSecurity bean. */
    private boolean centralizesSecurity(ApiModel code) {
        return code.blindSpots() != null && code.blindSpots().stream()
                .anyMatch(b -> b != null && (b.contains("SecurityFilterChain") || b.contains("HttpSecurity")));
    }

    /** True when the code endpoint binds query params loosely (a @ModelAttribute/command object, Pageable, @RequestParam
     *  Map bind-all, or @MatrixVariable) — the extractor emits no params + a blind spot for these, so the spec's
     *  flattened query params must NOT each be reported as PARAM_EXTRA. */
    private boolean bindsAllQueryParams(ApiModel code, Endpoint ce) {
        if (code.blindSpots() == null || ce.controllerClass() == null || ce.operationId() == null) {
            return false;
        }
        // The extractor's flatten blind spots all begin with `Controller <Class>.<method> ` — match that exact prefix
        // (with the trailing space as a right word boundary) rather than a loose contains(). A bare contains() lets a
        // method name that is a PREFIX of a sibling's (list ⊂ listByStatus) inherit the sibling's flatten-suppression
        // and silently drop a real PARAM_EXTRA on the shorter-named endpoint.
        String marker = "Controller " + ce.controllerClass() + "." + ce.operationId() + " ";
        return code.blindSpots().stream().anyMatch(b -> b != null && b.startsWith(marker)
                && (b.contains("binds all query params") || b.contains("@ModelAttribute") || b.contains("command object")
                    || b.contains("pagination") || b.contains("@MatrixVariable")));
    }

    // ---- helpers ----

    static boolean arrayRef(String ref) {
        return ref != null && ref.endsWith("[]");
    }

    static Finding finding(FindingType type, String endpoint, String specSource, String summary,
                            Endpoint codeEndpoint, Confidence confidence) {
        return build(type, endpoint, specSource, summary, codeEndpoint == null ? null : codeEndpoint.source(), confidence);
    }

    /**
     * Finding factory that attaches an explicit code {@link SourceRef} (e.g. a DTO field's own file + line) as the
     * evidence — so a schema-field finding traces to the exact field in the source, not just its endpoint.
     */
    static Finding fieldFinding(FindingType type, String endpoint, String specSource, String summary,
                                SourceRef codeEvidence, Confidence confidence) {
        return build(type, endpoint, specSource, summary, codeEvidence, confidence);
    }

    private static Finding build(FindingType type, String endpoint, String specSource, String summary,
                                 SourceRef codeEvidence, Confidence confidence) {
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
                .codeEvidence(codeEvidence)
                .build();
    }

    static Layer layerOf(FindingType t) {
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
    static Severity severityOf(FindingType t) {
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

    /**
     * Whether a discrepancy would break an existing consumer (an active shape/type/validation disagreement or an
     * availability loss) as opposed to additive / dead-spec / naming / documentation drift where the code is a
     * compatible superset of the spec. This drives the RELEASE verdict, NOT the fidelity score: a report with zero
     * breaking findings is release-safe even when the docs are behind. Conservative — anything not clearly additive
     * is treated as breaking so we never green-light a real risk.
     */
    public static boolean isBreaking(FindingType t) {
        return switch (t) {
            case OPENAPI_PARSE_ERROR, UNRESOLVED_REF,
                 MISSING_ENDPOINT, VERB_MISMATCH, SECURITY_MISMATCH,
                 SCHEMA_FIELD_TYPE_MISMATCH, PARAM_TYPE_MISMATCH, PARAM_REQUIRED_MISMATCH,
                 REQUEST_BODY_PRESENCE_MISMATCH, REQUEST_BODY_SCHEMA_MISMATCH, RESPONSE_SCHEMA_MISMATCH,
                 CONSTRAINT_GAP -> true;
            // SCHEMA_FIELD_MISSING (code returns an undocumented field), *_EXTRA, STATUS_CODE_MISSING, PARAM_MISSING,
            // PATH_VAR_NAME_MISMATCH, SPEC_DRIFT, CONSUMES_PRODUCES_MISMATCH, MISSING_INFO_FIELD, DESIGN_QUALITY,
            // TEST_BASIS_GAP — additive / dead-spec / documentation drift, non-breaking for a running consumer.
            default -> false;
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
