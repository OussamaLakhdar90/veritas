package ca.bnc.qe.veritas.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.engine.diff.DiffEngine;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.openapi.OpenApiModelExtractor;
import ca.bnc.qe.veritas.engine.openapi.SpecParse;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical probe of the real {@link DiffEngine}: runs many code/spec fixture pairs through the full
 * extract -> diff pipeline and prints, per case, whether the engine produced (or correctly suppressed)
 * the expected finding. Pure observation — the engine is never modified. One @Test, many cases.
 */
class DiffEngineComparisonProbe {

    private final JavaSpringExtractor javaExtractor = new JavaSpringExtractor();
    private final OpenApiModelExtractor specExtractor = new OpenApiModelExtractor();
    private final DiffEngine diffEngine = new DiffEngine();

    @Test
    void probe() throws Exception {
        // ============ FALSE-POSITIVE CHECKS (code & spec AGREE -> expect NO finding of that type) ============

        // FP1: enum field present on both sides — must NOT diff as object-vs-string (post enum-fix)
        run("fp_enum_field_agree", Kind.FALSE_POSITIVE_CHECK, FindingType.SCHEMA_FIELD_TYPE_MISMATCH,
                java(
                        ctrl("AccountController", "/accounts",
                                "  @GetMapping(\"/{id}\")\n  public Account get(@PathVariable String id) { return null; }\n"),
                        dto("Account", "  private String id;\n  private Status status;\n"),
                        enumType("Status", "ACTIVE", "CLOSED")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /accounts/{id}:
                            get:
                              parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Account'}}}}}
                        components:
                          schemas:
                            Account:
                              type: object
                              properties:
                                id: {type: string}
                                status: {type: string, enum: [ACTIVE, CLOSED]}
                            Status: {type: string, enum: [ACTIVE, CLOSED]}
                        """));

        // FP2: List<Foo> field modelled as type:array items $ref Foo — must NOT diff
        run("fp_list_field_agree", Kind.FALSE_POSITIVE_CHECK, FindingType.SCHEMA_FIELD_TYPE_MISMATCH,
                java(
                        ctrl("OrderController", "/orders",
                                "  @GetMapping(\"/{id}\")\n  public Order get(@PathVariable String id) { return null; }\n"),
                        dto("Order", "  private String id;\n  private java.util.List<Item> items;\n"),
                        dto("Item", "  private String sku;\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /orders/{id}:
                            get:
                              parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Order'}}}}}
                        components:
                          schemas:
                            Order:
                              type: object
                              properties:
                                id: {type: string}
                                items: {type: array, items: {$ref: '#/components/schemas/Item'}}
                            Item:
                              type: object
                              properties: {sku: {type: string}}
                        """));

        // FP3: path variable named the same on both sides — no PATH_VAR_NAME_MISMATCH
        run("fp_path_var_same", Kind.FALSE_POSITIVE_CHECK, FindingType.PATH_VAR_NAME_MISMATCH,
                java(ctrl("UserController", "/users",
                        "  @GetMapping(\"/{userId}\")\n  public String get(@PathVariable String userId) { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /users/{userId}:
                            get:
                              parameters: [{name: userId, in: path, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // FP4: identical query params — no PARAM_* findings
        run("fp_params_identical", Kind.FALSE_POSITIVE_CHECK, FindingType.PARAM_MISSING,
                java(ctrl("SearchController", "/search",
                        "  @GetMapping\n  public String find(@RequestParam(required = true) String q) { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /search:
                            get:
                              parameters: [{name: q, in: query, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // FP5: identical success status code — no STATUS_CODE_MISSING
        run("fp_status_identical", Kind.FALSE_POSITIVE_CHECK, FindingType.STATUS_CODE_MISSING,
                java(ctrl("PingController", "/ping",
                        "  @GetMapping\n  public String ping() { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /ping:
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // FP6: @PreAuthorize endpoint whose spec has matching security — no SECURITY_MISMATCH
        run("fp_security_match", Kind.FALSE_POSITIVE_CHECK, FindingType.SECURITY_MISMATCH,
                java(ctrl("AdminController", "/admin",
                        "  @org.springframework.security.access.prepost.PreAuthorize(\"hasRole('ADMIN')\")\n"
                                + "  @GetMapping\n  public String all() { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /admin:
                            get:
                              security: [{bearerAuth: []}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        components:
                          securitySchemes:
                            bearerAuth: {type: http, scheme: bearer}
                        """));

        // FP7: Map body modelled as object both sides — no REQUEST_BODY_PRESENCE_MISMATCH / RESPONSE_SCHEMA_MISMATCH
        run("fp_map_body_object", Kind.FALSE_POSITIVE_CHECK, FindingType.RESPONSE_SCHEMA_MISMATCH,
                java(ctrl("MetaController", "/meta",
                        "  @GetMapping\n  public java.util.Map<String,Object> meta() { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /meta:
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: object}}}}}
                        """));

        // FP8: method returning ResponseEntity<Foo> vs spec $ref Foo — no RESPONSE_SCHEMA_MISMATCH
        run("fp_responseentity_ref", Kind.FALSE_POSITIVE_CHECK, FindingType.RESPONSE_SCHEMA_MISMATCH,
                java(
                        ctrl("ProfileController", "/profile",
                                "  @GetMapping\n  public org.springframework.http.ResponseEntity<Profile> get() { return null; }\n"),
                        dto("Profile", "  private String name;\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /profile:
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Profile'}}}}}
                        components:
                          schemas:
                            Profile:
                              type: object
                              properties: {name: {type: string}}
                        """));

        // ============ FALSE-NEGATIVE CHECKS (code & spec DIFFER -> expect the right finding) ============

        // FN1: field type int (code) vs string (spec) -> SCHEMA_FIELD_TYPE_MISMATCH
        run("fn_field_type_int_vs_string", Kind.FALSE_NEGATIVE_CHECK, FindingType.SCHEMA_FIELD_TYPE_MISMATCH,
                java(
                        ctrl("WidgetController", "/widgets",
                                "  @GetMapping(\"/{id}\")\n  public Widget get(@PathVariable String id) { return null; }\n"),
                        dto("Widget", "  private int count;\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /widgets/{id}:
                            get:
                              parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Widget'}}}}}
                        components:
                          schemas:
                            Widget:
                              type: object
                              properties: {count: {type: string}}
                        """));

        // FN2: field in code missing from spec -> SCHEMA_FIELD_MISSING
        run("fn_field_missing_from_spec", Kind.FALSE_NEGATIVE_CHECK, FindingType.SCHEMA_FIELD_MISSING,
                java(
                        ctrl("CardController", "/cards",
                                "  @GetMapping(\"/{id}\")\n  public Card get(@PathVariable String id) { return null; }\n"),
                        dto("Card", "  private String pan;\n  private String secretCvv;\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /cards/{id}:
                            get:
                              parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Card'}}}}}
                        components:
                          schemas:
                            Card:
                              type: object
                              properties: {pan: {type: string}}
                        """));

        // FN3: endpoint in code missing from spec -> MISSING_ENDPOINT
        run("fn_endpoint_missing", Kind.FALSE_NEGATIVE_CHECK, FindingType.MISSING_ENDPOINT,
                java(ctrl("LegacyController", "/legacy",
                        "  @GetMapping(\"/secret\")\n  public String secret() { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /legacy/other:
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // FN4: verb mismatch (same path, GET in code vs POST in spec) -> VERB_MISMATCH
        run("fn_verb_mismatch", Kind.FALSE_NEGATIVE_CHECK, FindingType.VERB_MISMATCH,
                java(ctrl("ItemController", "/items",
                        "  @GetMapping(\"/do\")\n  public String act() { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /items/do:
                            post:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // FN5: required code field optional in spec -> CONSTRAINT_GAP (or PARAM_REQUIRED on params)
        run("fn_param_required_mismatch", Kind.FALSE_NEGATIVE_CHECK, FindingType.PARAM_REQUIRED_MISMATCH,
                java(ctrl("FilterController", "/filter",
                        "  @GetMapping\n  public String f(@RequestParam(required = true) String region) { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /filter:
                            get:
                              parameters: [{name: region, in: query, required: false, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // FN6: code @PreAuthorize endpoint with NO security in spec -> SECURITY_MISMATCH
        run("fn_security_missing_in_spec", Kind.FALSE_NEGATIVE_CHECK, FindingType.SECURITY_MISMATCH,
                java(ctrl("VaultController", "/vault",
                        "  @org.springframework.security.access.prepost.PreAuthorize(\"hasRole('ADMIN')\")\n"
                                + "  @GetMapping\n  public String open() { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /vault:
                            get:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // FN7: tighter code constraint (@Size max=10) not in spec -> CONSTRAINT_GAP
        run("fn_constraint_gap", Kind.FALSE_NEGATIVE_CHECK, FindingType.CONSTRAINT_GAP,
                java(
                        ctrl("NoteController", "/notes",
                                "  @GetMapping(\"/{id}\")\n  public Note get(@PathVariable String id) { return null; }\n"),
                        dto("Note", "  @jakarta.validation.constraints.Size(max = 10)\n  private String title;\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /notes/{id}:
                            get:
                              parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Note'}}}}}
                        components:
                          schemas:
                            Note:
                              type: object
                              properties: {title: {type: string}}
                        """));

        // FN8: code 201 vs spec 200 -> STATUS_CODE_MISSING
        run("fn_status_201_vs_200", Kind.FALSE_NEGATIVE_CHECK, FindingType.STATUS_CODE_MISSING,
                java(ctrl("CreateController", "/create",
                        "  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)\n"
                                + "  @PostMapping\n  public String make() { return null; }\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /create:
                            post:
                              responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
                        """));

        // ============ EDGE: compareSchema "object" guard ============
        // EDGE: code field type "object" vs spec "string" — the guard at DiffEngine line 271-272 skips when
        // either side is "object", so a real object-vs-string mismatch is SILENTLY SUPPRESSED (candidate FN).
        run("edge_object_vs_string_guard", Kind.FALSE_NEGATIVE_CHECK, FindingType.SCHEMA_FIELD_TYPE_MISMATCH,
                java(
                        ctrl("BlobController", "/blobs",
                                "  @GetMapping(\"/{id}\")\n  public Blob get(@PathVariable String id) { return null; }\n"),
                        // 'payload' is a non-collection object type (custom class not present) -> code type "object"
                        dto("Blob", "  private SomeOpaque payload;\n")),
                spec("""
                        openapi: 3.0.1
                        info: {title: t, version: '1'}
                        paths:
                          /blobs/{id}:
                            get:
                              parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                              responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Blob'}}}}}
                        components:
                          schemas:
                            Blob:
                              type: object
                              properties: {payload: {type: string}}
                        """));

        assertTrue(true);
    }

    // ---------- harness ----------

    private enum Kind { FALSE_POSITIVE_CHECK, FALSE_NEGATIVE_CHECK }

    private void run(String name, Kind kind, FindingType target, Path codeDir, String yaml) {
        try {
            ApiModel code = javaExtractor.extract(codeDir);
            SpecParse parse = specExtractor.extract("spec", yaml);
            boolean parsed = parse.parsed();
            ApiModel specModel = parse.model();
            List<Finding> findings = (specModel == null)
                    ? List.of() : diffEngine.diffCodeVsSpec(code, specModel);
            Set<String> types = findings.stream().map(f -> f.getType().name())
                    .collect(Collectors.toCollection(TreeSet::new));
            boolean produced = findings.stream().anyMatch(f -> f.getType() == target);

            boolean correct = (kind == Kind.FALSE_POSITIVE_CHECK) ? !produced : produced;
            String expected = (kind == Kind.FALSE_POSITIVE_CHECK)
                    ? "no " + target.name() : target.name();
            System.out.println("CASE " + name + " | " + kind + " | expected=" + expected
                    + " | actual=" + (types.isEmpty() ? "[]" : types)
                    + " | correct=" + correct
                    + " | parsed=" + parsed);
            findings.stream().filter(f -> f.getType() == FindingType.RESPONSE_SCHEMA_MISMATCH)
                    .forEach(f -> System.out.println("    DETAIL " + name + " :: " + f.getSummary()));
        } catch (Exception e) {
            System.out.println("CASE " + name + " | " + kind + " | expected=" + target.name()
                    + " | actual=ERROR:" + e + " | correct=false");
        }
    }

    // ---------- fixture builders ----------

    private Path java(String... files) throws Exception {
        Path dir = Files.createTempDirectory("probe-src-");
        int i = 0;
        for (String f : files) {
            String typeName = extractTypeName(f);
            Files.writeString(dir.resolve(typeName + ".java"), f);
            i++;
        }
        return dir;
    }

    private String extractTypeName(String src) {
        // find "class X" / "enum X" / "record X"
        for (String kw : new String[]{"class ", "enum ", "record ", "interface "}) {
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
        return "Unknown" + System.nanoTime();
    }

    private String ctrl(String className, String basePath, String body) {
        return "import org.springframework.web.bind.annotation.*;\n"
                + "@RestController\n@RequestMapping(\"" + basePath + "\")\n"
                + "public class " + className + " {\n" + body + "}\n";
    }

    private String dto(String name, String fields) {
        return "public class " + name + " {\n" + fields + "}\n";
    }

    private String enumType(String name, String... values) {
        return "public enum " + name + " { " + String.join(", ", values) + " }\n";
    }

    private String spec(String yaml) {
        return yaml;
    }
}
