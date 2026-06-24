package ca.bnc.qe.veritas.engine;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden discrepancy catalog — the anti-false-negative strategy for the contract finder. Each entry is a minimal
 * (code, spec) pair plus the finding types the engine MUST emit (anti-false-NEGATIVE) and MUST NOT emit
 * (anti-false-POSITIVE). It runs the REAL pipeline (extract → diff). Every discrepancy class — and every future MISS
 * found in the field — becomes a permanent row here, so the finder can never silently lose coverage again.
 *
 * <p>To lock in a newly-discovered miss: add a row with the reproducing fixture + the expected finding, watch it FAIL,
 * fix the engine, watch it pass. It is then a permanent regression guard.
 */
class GoldenDiscrepancyCatalogTest {

    private final JavaSpringExtractor javaExtractor = new JavaSpringExtractor();
    private final OpenApiModelExtractor specExtractor = new OpenApiModelExtractor();
    private final DiffEngine diffEngine = new DiffEngine();

    @ParameterizedTest(name = "{0}")
    @MethodSource("catalog")
    void engineCatchesEachDiscrepancyWithoutFalsePositives(Case c) throws Exception {
        ApiModel code = javaExtractor.extract(writeSources(c.javaSources()));
        SpecParse parse = specExtractor.extract("spec", c.specYaml());
        assertThat(parse.parsed()).as("%s — spec must parse (else the row passes vacuously)", c.id()).isTrue();

        List<Finding> findings = diffEngine.diffCodeVsSpec(code, parse.model());
        Set<FindingType> got = findings.stream().map(Finding::getType).collect(toSet());

        assertThat(got).as("%s — MUST emit", c.id()).containsAll(c.mustEmit());
        for (FindingType forbidden : c.mustNotEmit()) {
            assertThat(got).as("%s — must NOT emit", c.id()).doesNotContain(forbidden);
        }
        if (c.precision() != null) {
            assertThat(c.precision().test(findings))
                    .as("%s — precision check (a finding's locus/summary)", c.id()).isTrue();
        }
    }

    // ───────────────────────── the catalog ─────────────────────────

    static Stream<Case> catalog() {
        return Stream.of(
                // ===== false-NEGATIVE guards (code & spec DIFFER → the engine MUST report it) =====
                emit("field_type_drift", js(
                        ctrl("WidgetController", "/widgets", "  @GetMapping(\"/{id}\")\n  public Widget get(@PathVariable String id){return null;}\n"),
                        dto("Widget", "  private int count;\n")),
                        respSpec("/widgets/{id}", "Widget", "count: {type: string}"),
                        FindingType.SCHEMA_FIELD_TYPE_MISMATCH),

                emit("field_missing_from_spec", js(
                        ctrl("CardController", "/cards", "  @GetMapping(\"/{id}\")\n  public Card get(@PathVariable String id){return null;}\n"),
                        dto("Card", "  private String pan;\n  private String secretCvv;\n")),
                        respSpec("/cards/{id}", "Card", "pan: {type: string}"),
                        FindingType.SCHEMA_FIELD_MISSING),

                emit("endpoint_missing_from_spec", js(
                        ctrl("LegacyController", "/legacy", "  @GetMapping(\"/secret\")\n  public String s(){return null;}\n")),
                        plainSpec("/legacy/other"),
                        FindingType.MISSING_ENDPOINT),

                emit("verb_mismatch", js(
                        ctrl("ItemController", "/items", "  @GetMapping(\"/do\")\n  public String a(){return null;}\n")),
                        verbSpec("/items/do", "post"),
                        FindingType.VERB_MISMATCH),

                emit("security_missing_in_spec", js(
                        ctrl("VaultController", "/vault", "  @org.springframework.security.access.prepost.PreAuthorize(\"hasRole('ADMIN')\")\n  @GetMapping\n  public String o(){return null;}\n")),
                        plainSpec("/vault"),
                        FindingType.SECURITY_MISMATCH),

                // ===== false-POSITIVE guards (code & spec AGREE → the engine must stay SILENT) =====
                noEmit("enum_field_agrees", js(
                        ctrl("AccountController", "/accounts", "  @GetMapping(\"/{id}\")\n  public Account get(@PathVariable String id){return null;}\n"),
                        dto("Account", "  private String id;\n  private Status status;\n"),
                        enumType("Status", "ACTIVE", "CLOSED")),
                        respSpec("/accounts/{id}", "Account", "id: {type: string}\n        status: {type: string, enum: [ACTIVE, CLOSED]}"),
                        FindingType.SCHEMA_FIELD_TYPE_MISMATCH),

                noEmit("identical_structure_different_name", js(
                        ctrl("ProfileController", "/profile", "  @GetMapping\n  public Wrapper get(){return null;}\n"),
                        dto("Wrapper", "  private String password;\n")),
                        respRefSpec("/profile", "policies", "policies", "password: {type: string}"),
                        FindingType.RESPONSE_SCHEMA_MISMATCH, FindingType.SCHEMA_FIELD_MISSING),

                noEmit("identical_params", js(
                        ctrl("SearchController", "/search", "  @GetMapping\n  public String f(@RequestParam(required=true) String q){return null;}\n")),
                        paramSpec("/search", "q", "query", true, "string", null),
                        FindingType.PARAM_MISSING, FindingType.PARAM_TYPE_MISMATCH),

                // ===== the 4 field-found MISSES (permanent guards) =====
                // Miss 1: undocumented response field on a NESTED, differently-named DTO (excludeAttributes).
                emitPrecise("miss1_renamed_nested_schema_field",
                        js(
                                ctrl("PolicyController", "/policies", "  @GetMapping(\"/password\")\n  public PasswordPolicy pw(){return null;}\n"),
                                dto("PasswordPolicy", "  private int minLength;\n  private PasswordComplexity complexity;\n"),
                                dto("PasswordComplexity", "  private int minLength;\n  private String[] excludeAttributes;\n")),
                        """
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /policies/password:
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/policies-password'}}}}}
                        components:
                          schemas:
                            policies-password:
                              type: object
                              properties:
                                minLength: {type: integer}
                                complexity: {$ref: '#/components/schemas/policies-password-complexity'}
                            policies-password-complexity:
                              type: object
                              properties:
                                minLength: {type: integer}
                        """,
                        f -> f.stream().anyMatch(x -> x.getType() == FindingType.SCHEMA_FIELD_MISSING
                                && x.getSummary() != null && x.getSummary().contains("excludeAttributes")),
                        FindingType.SCHEMA_FIELD_MISSING),

                // Miss 2: a code enum param vs a bare-string spec param → CONSTRAINT_GAP (enum drift is now testable).
                emit("miss2_enum_param_vs_bare_string",
                        js(
                                ctrl("LookupController", "/accounts", "  @GetMapping(\"/lookup\")\n  public String l(@RequestParam Context context){return null;}\n"),
                                enumType("Context", "INDIVIDUAL", "ENNBIN", "ENTERPRISE", "NBC2", "NOT_PROVIDED")),
                        paramSpec("/accounts/lookup", "context", "query", false, "string", null),
                        FindingType.CONSTRAINT_GAP),

                // Miss 2b: the spec's allowed set lives ONLY in description prose ("Must be one of [...]"); the code enum
                // is missing a spec-advertised value (NBC4) and accepts values the spec omits -> value-level CONSTRAINT_GAP.
                emitPrecise("miss2b_enum_value_drift_from_prose",
                        js(
                                "import org.springframework.web.bind.annotation.*;\n@RestController\n@RequestMapping(\"/accounts\")\n"
                                        + "public class CtxController {\n  @GetMapping(\"/lookup\")\n"
                                        + "  public String l(@RequestHeader(value=\"context\", required=false) Context context){return null;}\n}\n",
                                enumType("Context", "INDIVIDUAL", "ENNBIN", "ENTERPRISE", "NBC2", "NOT_PROVIDED", "SYSTEM", "NBINF")),
                        """
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /accounts/lookup:
                            get:
                              parameters:
                                - name: context
                                  in: header
                                  required: false
                                  description: "Must be one of [INDIVIDUAL, SYSTEM, NBC2, NBC4] (case insensitive)."
                                  schema: {type: string}
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """,
                        f -> f.stream().anyMatch(x -> x.getType() == FindingType.CONSTRAINT_GAP
                                && x.getSummary() != null && x.getSummary().contains("NBC4") && x.getSummary().contains("spec=")),
                        FindingType.CONSTRAINT_GAP),

                // Miss 2c: a $ref-component "context" param whose SCHEMA is itself a $ref to a reusable enum schema
                // (schema: {$ref: '#/components/schemas/ContextEnum'}) — the common BNC shape. setResolve(true)
                // resolves the parameter $ref (name is fine → no false PARAM_MISSING/EXTRA) but does NOT dereference
                // the param's schema $ref, so the spec enum [INDIVIDUAL, SYSTEM, NBC2, NBC4] was invisible and only a
                // generic "code has an enum, spec has none" gap fired — the value-level drift (spec advertises NBC4 the
                // code rejects; code accepts ENNBIN/ENTERPRISE/NOT_PROVIDED/NBINF) was missed. toParam now resolves the
                // param's schema $ref against components/schemas, so the drift surfaces as a precise CONSTRAINT_GAP.
                new Case("miss2c_enum_value_drift_from_component_ref",
                        js(
                                ctrl("CtxRefController", "/accounts",
                                        "  @GetMapping(\"/lookup\")\n  public String l(@RequestParam(value=\"context\", required=false) Context context){return null;}\n"),
                                enumType("Context", "INDIVIDUAL", "ENNBIN", "ENTERPRISE", "NBC2", "NOT_PROVIDED", "SYSTEM", "NBINF")),
                        """
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /accounts/lookup:
                            parameters:
                              - $ref: '#/components/parameters/context'
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        components:
                          parameters:
                            context:
                              name: context
                              in: query
                              required: false
                              schema: {$ref: '#/components/schemas/ContextEnum'}
                          schemas:
                            ContextEnum:
                              type: string
                              enum: [INDIVIDUAL, SYSTEM, NBC2, NBC4]
                        """,
                        Set.of(FindingType.CONSTRAINT_GAP),
                        Set.of(FindingType.PARAM_MISSING, FindingType.PARAM_EXTRA),
                        f -> f.stream().anyMatch(x -> x.getType() == FindingType.CONSTRAINT_GAP
                                && x.getSummary() != null && x.getSummary().contains("NBC4")
                                && x.getSummary().contains("spec="))),

                // Miss 2d: the same drift but the param's schema reaches the enum through MORE THAN ONE $ref hop
                // (schema:{$ref:ContextRef} → ContextRef:{$ref:ContextEnum} → ContextEnum:{enum:[…]}). A single-hop
                // resolver stops at ContextRef (no enum) and misses it; toParam now follows the schema $ref chain
                // transitively (cycle-guarded), so the value drift still surfaces as a precise CONSTRAINT_GAP.
                new Case("miss2d_enum_value_drift_transitive_ref",
                        js(
                                ctrl("CtxRefController", "/accounts",
                                        "  @GetMapping(\"/lookup\")\n  public String l(@RequestParam(value=\"context\", required=false) Context context){return null;}\n"),
                                enumType("Context", "INDIVIDUAL", "ENNBIN", "ENTERPRISE", "NBC2", "NOT_PROVIDED", "SYSTEM", "NBINF")),
                        """
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /accounts/lookup:
                            parameters:
                              - $ref: '#/components/parameters/context'
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        components:
                          parameters:
                            context:
                              name: context
                              in: query
                              required: false
                              schema: {$ref: '#/components/schemas/ContextRef'}
                          schemas:
                            ContextRef:
                              $ref: '#/components/schemas/ContextEnum'
                            ContextEnum:
                              type: string
                              enum: [INDIVIDUAL, SYSTEM, NBC2, NBC4]
                        """,
                        Set.of(FindingType.CONSTRAINT_GAP),
                        Set.of(FindingType.PARAM_MISSING, FindingType.PARAM_EXTRA),
                        f -> f.stream().anyMatch(x -> x.getType() == FindingType.CONSTRAINT_GAP
                                && x.getSummary() != null && x.getSummary().contains("NBC4")
                                && x.getSummary().contains("spec="))),

                // 2e: GUARD mirroring the REAL BNC ciam-policies contract shape — a $ref-component HEADER param
                // ('#/components/parameters/context') whose allowed values live ONLY in the description prose
                // ("Must be one of [INDIVIDUAL, SYSTEM, NBC2, NBC4]"), schema:{type:string} (no enum). The SPEC side
                // is ALREADY handled: swagger-parser's setResolve makes the resolved param's description visible and
                // PROSE_ENUM lifts the set, so against the code enum it yields a precise CONSTRAINT_GAP. This row
                // locks that in (it passes today, independent of the schema-$ref resolution in #9/#14). Confirmed via
                // the real contract: the spec side is NOT the live miss — that gap is on the CODE side (the controller
                // must expose `context` as a bindable param whose enum the extractor can read).
                new Case("miss2e_enum_value_drift_prose_on_component_ref_header_param",
                        js(
                                "import org.springframework.web.bind.annotation.*;\n@RestController\n@RequestMapping(\"/policies\")\n"
                                        + "public class PolicyController {\n  @GetMapping\n"
                                        + "  public String def(@RequestHeader(value=\"context\", required=false) Context context){return null;}\n}\n",
                                enumType("Context", "INDIVIDUAL", "ENNBIN", "ENTERPRISE", "NBC2", "NOT_PROVIDED", "SYSTEM", "NBINF")),
                        """
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /policies:
                            get:
                              parameters:
                                - $ref: '#/components/parameters/context'
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        components:
                          parameters:
                            context:
                              name: context
                              in: header
                              required: false
                              description: |
                                The name of the context to run the request (target Okta tenant).
                                Must be one of [INDIVIDUAL, SYSTEM, NBC2, NBC4] (case insensitive).
                              schema:
                                type: string
                              example: INDIVIDUAL
                        """,
                        Set.of(FindingType.CONSTRAINT_GAP),
                        Set.of(FindingType.PARAM_MISSING, FindingType.PARAM_EXTRA),
                        f -> f.stream().anyMatch(x -> x.getType() == FindingType.CONSTRAINT_GAP
                                && x.getSummary() != null && x.getSummary().contains("NBC4")
                                && x.getSummary().contains("spec="))),

                // Miss 3: error-response media-type drift — advice emits application/problem+json, spec says application/json.
                emit("miss3_error_media_type_drift",
                        js(
                                ctrl("DocController", "/docs", "  @GetMapping(\"/{id}\")\n  public Doc get(@PathVariable String id){return null;}\n"),
                                dto("Doc", "  private String id;\n"),
                                "import org.springframework.http.*;\nimport org.springframework.web.bind.annotation.*;\n"
                                        + "@RestControllerAdvice\npublic class Advice {\n"
                                        + "  @ExceptionHandler(RuntimeException.class)\n"
                                        + "  public ResponseEntity<Doc> handle(RuntimeException e){\n"
                                        + "    return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(null);\n"
                                        + "  }\n}\n"),
                        """
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /docs/{id}:
                            get:
                              parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                              responses:
                                '200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Doc'}}}}
                                '404': {description: not found, content: {application/json: {schema: {type: object}}}}
                        components:
                          schemas:
                            Doc:
                              type: object
                              properties: {id: {type: string}}
                        """,
                        FindingType.CONSUMES_PRODUCES_MISMATCH),

                // Miss 4: a 404 thrown INTER-PROCEDURALLY (in a service the controller calls) is reachable on
                // GET /policies (spec omits it) but NOT on GET /policies/{appId} (spec documents it) → scored MEDIUM
                // on the base op only, no false positive on the {appId} op.
                emitPrecise("miss4_reachable_404_one_hop",
                        js(
                                "import org.springframework.web.bind.annotation.*;\n@RestController\n@RequestMapping(\"/policies\")\n"
                                        + "public class PolicyController {\n  private final PolicyRulesService service;\n"
                                        + "  public PolicyController(PolicyRulesService s){this.service=s;}\n"
                                        + "  @GetMapping\n  public String def(){return service.getAppKey(null);}\n"
                                        + "  @GetMapping(\"/{appId}\")\n  public String byId(@PathVariable String appId){return service.getAppKey(appId);}\n}\n",
                                "import org.springframework.stereotype.Service;\n@Service\npublic class PolicyRulesService {\n"
                                        + "  public String getAppKey(String appId){ throw new PolicyNotFoundException(); }\n}\n",
                                "public class PolicyNotFoundException extends RuntimeException {}\n",
                                "import org.springframework.http.HttpStatus;\nimport org.springframework.web.bind.annotation.*;\n"
                                        + "@RestControllerAdvice\npublic class Advice {\n  @ExceptionHandler(PolicyNotFoundException.class)\n"
                                        + "  @ResponseStatus(HttpStatus.NOT_FOUND)\n  public String h(PolicyNotFoundException e){return null;}\n}\n"),
                        """
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /policies:
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                          /policies/{appId}:
                            get:
                              parameters: [{name: appId, in: path, required: true, schema: {type: string}}]
                              responses:
                                '200': {description: ok, content: {application/json: {schema: {type: string}}}}
                                '404': {description: not found, content: {application/json: {schema: {type: object}}}}
                        """,
                        f -> {
                            List<Finding> notFounds = f.stream()
                                    .filter(x -> x.getType() == FindingType.STATUS_CODE_MISSING
                                            && x.getSummary() != null && x.getSummary().contains("404")).toList();
                            boolean baseScored = notFounds.stream().anyMatch(x -> x.getEndpoint() != null
                                    && x.getEndpoint().contains("/policies") && !x.getEndpoint().contains("{")
                                    && "MEDIUM".equals(x.getConfidence().name()));
                            boolean noIdFinding = notFounds.stream()
                                    .noneMatch(x -> x.getEndpoint() != null && x.getEndpoint().contains("{appId}"));
                            return baseScored && noIdFinding;
                        },
                        FindingType.STATUS_CODE_MISSING));
    }

    // ───────────────────────── case model + factories ─────────────────────────

    record Case(String id, String[] javaSources, String specYaml, Set<FindingType> mustEmit,
                Set<FindingType> mustNotEmit, Predicate<List<Finding>> precision) {
        @Override public String toString() {
            return id;
        }
    }

    static Case emit(String id, String[] js, String spec, FindingType... must) {
        return new Case(id, js, spec, Set.of(must), Set.of(), null);
    }

    static Case noEmit(String id, String[] js, String spec, FindingType... mustNot) {
        return new Case(id, js, spec, Set.of(), Set.of(mustNot), null);
    }

    static Case emitPrecise(String id, String[] js, String spec, Predicate<List<Finding>> p, FindingType... must) {
        return new Case(id, js, spec, Set.of(must), Set.of(), p);
    }

    // ───────────────────────── fixture builders (ported from DiffEngineComparisonProbe) ─────────────────────────

    static String[] js(String... files) {
        return files;
    }

    static String ctrl(String className, String basePath, String body) {
        return "import org.springframework.web.bind.annotation.*;\n@RestController\n@RequestMapping(\"" + basePath + "\")\n"
                + "public class " + className + " {\n" + body + "}\n";
    }

    static String dto(String name, String fields) {
        return "public class " + name + " {\n" + fields + "}\n";
    }

    static String enumType(String name, String... values) {
        return "public enum " + name + " { " + String.join(", ", values) + " }\n";
    }

    /** A spec where {path}.get returns 200 referencing component {schema}, with the given property lines. */
    static String respSpec(String path, String schema, String properties) {
        return "openapi: 3.0.1\ninfo: {title: t, version: '1'}\npaths:\n  " + path + ":\n    get:\n"
                + "      responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/"
                + schema + "'}}}}}\ncomponents:\n  schemas:\n    " + schema + ":\n      type: object\n      properties:\n        "
                + properties + "\n";
    }

    /** A spec whose 200 references a component named {specSchema} (which may differ from the code DTO name). */
    static String respRefSpec(String path, String specSchema, String compName, String properties) {
        return "openapi: 3.0.1\ninfo: {title: t, version: '1'}\npaths:\n  " + path + ":\n    get:\n"
                + "      responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/"
                + specSchema + "'}}}}}\ncomponents:\n  schemas:\n    " + compName + ":\n      type: object\n      properties:\n        "
                + properties + "\n";
    }

    static String plainSpec(String path) {
        return "openapi: 3.0.1\ninfo: {title: t, version: '1'}\npaths:\n  " + path + ":\n    get:\n"
                + "      responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}\n";
    }

    static String verbSpec(String path, String verb) {
        return "openapi: 3.0.1\ninfo: {title: t, version: '1'}\npaths:\n  " + path + ":\n    " + verb + ":\n"
                + "      responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}\n";
    }

    static String paramSpec(String path, String name, String in, boolean required, String type, String fmt) {
        String schema = "{type: " + type + (fmt == null ? "" : ", format: " + fmt) + "}";
        return "openapi: 3.0.1\ninfo: {title: t, version: '1'}\npaths:\n  " + path + ":\n    get:\n"
                + "      parameters: [{name: " + name + ", in: " + in + ", required: " + required + ", schema: " + schema + "}]\n"
                + "      responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}\n";
    }

    private Path writeSources(String[] files) throws Exception {
        Path dir = Files.createTempDirectory("golden-src-");
        for (String f : files) {
            Files.writeString(dir.resolve(typeName(f) + ".java"), f);
        }
        return dir;
    }

    private static String typeName(String src) {
        for (String kw : new String[] {"class ", "enum ", "record ", "interface "}) {
            int idx = src.indexOf(kw);
            if (idx >= 0) {
                int start = idx + kw.length();
                int end = start;
                while (end < src.length() && (Character.isLetterOrDigit(src.charAt(end)) || src.charAt(end) == '_')) {
                    end++;
                }
                return src.substring(start, end);
            }
        }
        return "Unknown" + Math.abs(src.hashCode());
    }
}
