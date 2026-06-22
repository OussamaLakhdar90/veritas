package ca.bnc.qe.veritas.engine.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
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
            Endpoint sByPath = specByPath.get(normPath(ce.pathTemplate()));
            if (sByPath != null) {
                findings.add(finding(FindingType.VERB_MISMATCH, label(ce), spec.source(),
                        "Code exposes " + ce.method() + " " + ce.pathTemplate()
                                + " but the spec defines " + sByPath.method() + " for that path",
                        ce, Confidence.HIGH));
            } else {
                findings.add(finding(FindingType.MISSING_ENDPOINT, label(ce), spec.source(),
                        "Endpoint " + ce.signature() + " exists in code but is missing from the spec",
                        ce, Confidence.HIGH));
            }
        }
        for (Endpoint se : spec.endpoints()) {
            if (!codeByKey.containsKey(key(se)) && !codeByPath.containsKey(normPath(se.pathTemplate()))) {
                findings.add(finding(FindingType.EXTRA_ENDPOINT, se.signature(), spec.source(),
                        "Endpoint " + se.signature() + " is in the spec but not found in code (dead spec?)",
                        null, Confidence.MEDIUM));
            }
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
        return loc + ":" + (p.name() == null ? "" : p.name());
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
        // spec documents a 2xx success code the code never returns (conservative: success codes only,
        // since code error responses aren't extracted until @ControllerAdvice support)
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
        // to JSON and declare nothing — comparing those would be noise). Compared as case-insensitive sets.
        mediaTypeMismatch(findings, ce, spec, "produces", ce.produces(), se.produces());
        mediaTypeMismatch(findings, ce, spec, "consumes", ce.consumes(), se.consumes());

        // success-response body schema differs between code and spec
        String codeRef = successSchemaRef(ce);
        String specRef = successSchemaRef(se);
        if (codeRef != null && specRef != null && !normRef(codeRef).equals(normRef(specRef))) {
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

    private String normRef(String ref) {
        return ref == null ? null : ref.replace("[]", "").toLowerCase(Locale.ROOT);
    }

    /** Flag a consumes/produces divergence only when the code side declares media types (else it's noise). */
    private void mediaTypeMismatch(List<Finding> findings, Endpoint ce, ApiModel spec, String which,
                                   java.util.List<String> code, java.util.List<String> specMt) {
        if (code == null || code.isEmpty() || specMt == null || specMt.isEmpty()) {
            return;
        }
        if (!mediaSet(code).equals(mediaSet(specMt))) {
            findings.add(finding(FindingType.CONSUMES_PRODUCES_MISMATCH, label(ce), spec.source(),
                    which + " media types — code " + code + " vs spec " + specMt, ce, Confidence.LOW));
        }
    }

    /** Media types compared by base type, case-insensitive, ignoring parameters (e.g. {@code ;charset=utf-8}). */
    private java.util.Set<String> mediaSet(java.util.List<String> v) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String x : v) {
            if (x == null) {
                continue;
            }
            String base = x.toLowerCase(Locale.ROOT);
            int semi = base.indexOf(';');
            base = (semi >= 0 ? base.substring(0, semi) : base).trim();
            if (!base.isEmpty()) {
                out.add(base);
            }
        }
        return out;
    }

    /** True when the code model flagged that authorization is centralized in a SecurityFilterChain/HttpSecurity bean. */
    private boolean centralizesSecurity(ApiModel code) {
        return code.blindSpots() != null && code.blindSpots().stream()
                .anyMatch(b -> b != null && (b.contains("SecurityFilterChain") || b.contains("HttpSecurity")));
    }

    /** First constraint keyword whose value differs between two non-empty sets, or null if equivalent. */
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
        if (!Objects.equals(c.pattern(), s.pattern())) {
            return "pattern code=" + c.pattern() + " spec=" + s.pattern();
        }
        if (!sameValueSet(c.enumValues(), s.enumValues())) {
            return "enum code=" + c.enumValues() + " spec=" + s.enumValues();
        }
        return null;
    }

    /** Enum equivalence is by VALUE SET, case-insensitive — declaration order and casing differences are not drift. */
    private boolean sameValueSet(java.util.List<String> a, java.util.List<String> b) {
        return normSet(a).equals(normSet(b));
    }

    private java.util.Set<String> normSet(java.util.List<String> v) {
        java.util.Set<String> out = new java.util.HashSet<>();
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

    private Severity severityOf(FindingType t) {
        return switch (t) {
            case OPENAPI_PARSE_ERROR, MISSING_ENDPOINT, VERB_MISMATCH -> Severity.CRITICAL;
            case EXTRA_ENDPOINT, PATH_VAR_NAME_MISMATCH, PARAM_MISSING, PARAM_TYPE_MISMATCH,
                 PARAM_REQUIRED_MISMATCH, REQUEST_BODY_PRESENCE_MISMATCH, STATUS_CODE_MISSING,
                 RESPONSE_SCHEMA_MISMATCH, SCHEMA_FIELD_MISSING, SCHEMA_FIELD_TYPE_MISMATCH,
                 CONSTRAINT_GAP, SECURITY_MISMATCH, UNRESOLVED_REF, SPEC_DRIFT -> Severity.MAJOR;
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
        String p = path.toLowerCase(Locale.ROOT).replaceAll("\\{[^}]*\\}", "{}");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "/" : p;
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
