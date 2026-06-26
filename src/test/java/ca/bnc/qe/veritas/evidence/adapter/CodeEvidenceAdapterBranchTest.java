package ca.bnc.qe.veritas.evidence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.HttpMethod;
import ca.bnc.qe.veritas.engine.model.ParamLocation;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.junit.jupiter.api.Test;

/**
 * Branch-maximising companion to {@link CodeEvidenceAdapterTest}: drives the fallback/empty/null branches that the
 * happy-path fixture there does not reach — blank controllerClass, null schemas map, enum-only and unshaped schemas,
 * null/blank caveats, optional (non-required) params/fields, null source refs and missing start lines, and the
 * non-{@code .java} / no-directory classFromSource cases. Assertions check the exact derived values (mutation-ready).
 */
class CodeEvidenceAdapterBranchTest {

    private final CodeEvidenceAdapter adapter = new CodeEvidenceAdapter();

    private static EvidenceUnit byType(SourceExtraction r, UnitType type) {
        return r.units().stream().filter(u -> u.type() == type).findFirst().orElseThrow();
    }

    // ----- controllerClass fallback branches (the ternary in extract) -----

    @Test
    void blankControllerClassFallsBackToTheSourceFileBasename() {
        // controllerClass is present but blank → !isBlank() is false → the ternary takes classFromSource(source).
        SourceRef src = SourceRef.code("src/main/java/ca/bnc/AuthController.java", 12, 20, null);
        Endpoint ep = new Endpoint(HttpMethod.POST, "/login", "login", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), src, "   ");   // blank, not null
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        // The id uses "AuthController" (from the file), NOT the blank class.
        assertThat(endpoint.id()).isEqualTo("CODE:AuthController#POST /login");
        // The lowercased class hint also comes from the file basename.
        assertThat(endpoint.hints()).contains("authcontroller", "login");
    }

    @Test
    void nullControllerClassFallsBackToTheSourceFileBasename() {
        // controllerClass null → first half of && short-circuits false → classFromSource(source).
        SourceRef src = SourceRef.code("pkg/PolicyController.java", 5, 9, null);
        Endpoint ep = new Endpoint(HttpMethod.DELETE, "/policies/{id}", "del", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), src);   // back-compat ctor → controllerClass=null
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        assertThat(endpoint.id()).isEqualTo("CODE:PolicyController#DELETE /policies/{id}");
        // Controller-class hint + the literal path-noun "policies"; the "{id}" path var is skipped by Hints.fromPath.
        assertThat(endpoint.hints())
                .contains("policycontroller", "policies")
                .doesNotContain("id");
    }

    // ----- endpointText optional branches: no params, no responses, no security, non-required param -----

    @Test
    void endpointTextOmitsParamsResponsesAndSecuritySectionsWhenAllAreEmpty() {
        SourceRef src = SourceRef.code("Routes.java", 1, 1, "x");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/health", "health", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), src, "HealthController");
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        // Only the signature — none of the optional clauses appended.
        assertThat(endpoint.text()).isEqualTo("GET /health");
        assertThat(endpoint.text())
                .doesNotContain("parameters:")
                .doesNotContain("responses:")
                .doesNotContain("secured:");
    }

    @Test
    void optionalParamIsRenderedWithoutTheRequiredMarkerAndResponseWithoutSchemaRef() {
        SourceRef src = SourceRef.code("src/SearchController.java", 40, 44, null);
        ParamModel q = new ParamModel("q", ParamLocation.QUERY, "string", null, false, null, src);   // required=false
        ResponseModel noSchema = new ResponseModel(204, null, List.of(), "RETURN", src);   // schemaRef=null
        Endpoint ep = new Endpoint(HttpMethod.GET, "/search", "search", List.of(q), null,
                List.of(noSchema), List.of(), List.of(), List.of(), src, "SearchController");
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        // required=false → no ", required" suffix on the parameter.
        assertThat(endpoint.text())
                .contains("parameters: q (QUERY)")
                .doesNotContain("q (QUERY, required)")
                // schemaRef=null → status code only, no trailing ref.
                .contains("responses: 204")
                .doesNotContain("204 ")
                // no security → no secured clause.
                .doesNotContain("secured:");
    }

    @Test
    void multipleParamsResponsesAndSecurityAreJoinedInOrder() {
        SourceRef src = SourceRef.code("src/OrderController.java", 7, 11, null);
        ParamModel id = new ParamModel("id", ParamLocation.PATH, "string", null, true, null, src);
        ParamModel verbose = new ParamModel("verbose", ParamLocation.QUERY, "boolean", null, false, null, src);
        ResponseModel ok = new ResponseModel(200, "Order", List.of(), "RETURN", src);
        ResponseModel err = new ResponseModel(403, "Problem", List.of(), "EXCEPTION_HANDLER", src);
        Endpoint ep = new Endpoint(HttpMethod.PUT, "/orders/{id}", "update", List.of(id, verbose), null,
                List.of(ok, err), List.of(), List.of(), List.of("ROLE_USER", "SCOPE_orders"), src, "OrderController");
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        assertThat(endpoint.text())
                .contains("parameters: id (PATH, required), verbose (QUERY)")
                .contains("responses: 200 Order, 403 Problem")
                .contains("secured: ROLE_USER, SCOPE_orders");
    }

    // ----- schema branches: null schemas map, enum-only, unshaped (skipped), optional field -----

    @Test
    void nullSchemasMapProducesNoDtoUnits() {
        SourceRef src = SourceRef.code("C.java", 1, 1, null);
        Endpoint ep = new Endpoint(HttpMethod.GET, "/ping", "ping", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), src, "C");
        // schemas == null → the whole `if (model.schemas() != null)` block is skipped.
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), null);

        SourceExtraction r = adapter.extract(model);
        assertThat(r.units()).hasSize(1);   // just the endpoint
        assertThat(r.units().stream().anyMatch(u -> u.type() == UnitType.DTO_CONSTRAINT)).isFalse();
    }

    @Test
    void enumOnlySchemaIsShapedAndRendersAllowedValues() {
        SourceRef src = SourceRef.spec("repo", "#/components/schemas/Status", "{}");
        // No fields (empty list), but enum values present → isShaped true via the enum arm.
        SchemaModel status = new SchemaModel("Status", "string", List.of(), List.of("ACTIVE", "CLOSED"), src);
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(),
                Map.of("Status", status), List.of());

        SourceExtraction r = adapter.extract(model);
        EvidenceUnit dto = byType(r, UnitType.DTO_CONSTRAINT);
        assertThat(dto.id()).isEqualTo("CODE:Status.schema");
        assertThat(dto.title()).isEqualTo("Schema Status");
        // enum-only: no field list, but the allowed-values clause is present.
        assertThat(dto.text())
                .isEqualTo("Schema Status; allowed values: ACTIVE, CLOSED")
                .doesNotContain("Status: ");
    }

    @Test
    void unshapedSchemasWithNeitherFieldsNorEnumValuesAreSkipped() {
        SourceRef src = SourceRef.code("S.java", 1, 1, null);
        SchemaModel emptyLists = new SchemaModel("EmptyBoth", "object", List.of(), List.of(), src);
        SchemaModel nullLists = new SchemaModel("NullBoth", "object", null, null, src);
        Map<String, SchemaModel> schemas = new LinkedHashMap<>();
        schemas.put("EmptyBoth", emptyLists);
        schemas.put("NullBoth", nullLists);
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(), schemas);

        SourceExtraction r = adapter.extract(model);
        // Neither schema is shaped → no DTO units → no units at all → fetched dropped to 0.
        assertThat(r.units()).isEmpty();
        assertThat(r.fetched()).isZero();
        assertThat(r.requested()).isEqualTo(1);
    }

    @Test
    void schemaWithOptionalFieldOmitsTheRequiredMarkerAndStillRendersFieldType() {
        SourceRef src = SourceRef.code("S.java", 1, 1, null);
        FieldModel optional = new FieldModel("nickname", "string", null, false, null, null, src);   // required=false
        SchemaModel schema = new SchemaModel("Profile", "object", List.of(optional), List.of(), src);
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(), Map.of("Profile", schema));

        EvidenceUnit dto = byType(adapter.extract(model), UnitType.DTO_CONSTRAINT);
        assertThat(dto.text())
                .isEqualTo("Schema Profile: nickname (string)")   // no ", required"
                .doesNotContain("required");
    }

    @Test
    void schemaWithBothFieldsAndEnumValuesRendersBothClauses() {
        SourceRef src = SourceRef.code("S.java", 1, 1, null);
        FieldModel f = new FieldModel("code", "string", null, true, null, null, src);
        SchemaModel schema = new SchemaModel("Coupon", "object", List.of(f), List.of("X", "Y"), src);
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(), Map.of("Coupon", schema));

        EvidenceUnit dto = byType(adapter.extract(model), UnitType.DTO_CONSTRAINT);
        assertThat(dto.text()).isEqualTo("Schema Coupon: code (string, required); allowed values: X, Y");
    }

    // ----- caveat branches: null and blank are skipped, valid one kept -----

    @Test
    void nullAndBlankCaveatsAreSkippedWhileValidOnesBecomeUnits() {
        // First entry null, second blank → both skipped; only the third produces a GLOBAL_CAVEAT unit.
        List<String> blindSpots = new java.util.ArrayList<>();
        blindSpots.add(null);
        blindSpots.add("   ");
        blindSpots.add("Reflection-based routing not statically resolvable.");
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(), Map.of(), blindSpots);

        SourceExtraction r = adapter.extract(model);
        List<EvidenceUnit> caveats = r.units().stream()
                .filter(u -> u.type() == UnitType.GLOBAL_CAVEAT).toList();
        assertThat(caveats).hasSize(1);
        EvidenceUnit caveat = caveats.get(0);
        assertThat(caveat.title()).isEqualTo("Analysis caveat");
        assertThat(caveat.text()).isEqualTo("Reflection-based routing not statically resolvable.");
        assertThat(caveat.link()).isNull();   // caveats carry no source link
        assertThat(caveat.hints()).containsExactly("cross-cutting");
        assertThat(caveat.source()).isEqualTo(SourceKind.CODE);
    }

    // ----- refLink branches: null source, null location, and present-but-no-start-line -----

    @Test
    void endpointWithNullSourceHasNoLink() {
        // source == null → refLink returns null.
        Endpoint ep = new Endpoint(HttpMethod.GET, "/x", "x", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), null, "XController");
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        assertThat(endpoint.link()).isNull();
        // controllerClass is used directly since classFromSource(null) is never consulted here.
        assertThat(endpoint.id()).isEqualTo("CODE:XController#GET /x");
    }

    @Test
    void sourceWithLocationButNoStartLineYieldsLinkWithoutLineSuffix() {
        // location present, startLine null → refLink returns the bare location (no ":line").
        SourceRef src = SourceRef.spec("repo", "#/paths/~1items/get", "{}");   // spec() → startLine=null
        Endpoint ep = new Endpoint(HttpMethod.GET, "/items", "items", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), src, "ItemController");
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        assertThat(endpoint.link()).isEqualTo("#/paths/~1items/get");   // no trailing :line
    }

    @Test
    void sourceWithNullLocationYieldsNoLink() {
        // A SourceRef whose location is null → the `src.location() == null` arm of refLink.
        SourceRef src = new SourceRef("code", null, 99, 100, "snippet");
        Endpoint ep = new Endpoint(HttpMethod.GET, "/y", "y", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), src, "YController");
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep), Map.of());

        EvidenceUnit endpoint = byType(adapter.extract(model), UnitType.ENDPOINT);
        assertThat(endpoint.link()).isNull();
    }

    // ----- classFromSource branches not hit by the sibling test -----

    @Test
    void classFromSourceReturnsCodeWhenLocationIsNull() {
        // location == null arm (src non-null).
        SourceRef src = new SourceRef("code", null, 1, 2, null);
        assertThat(CodeEvidenceAdapter.classFromSource(src)).isEqualTo("Code");
    }

    @Test
    void classFromSourceKeepsNonJavaBasenameAndHandlesBackslashesAndNoDirectory() {
        // Backslash path, non-.java extension → basename kept verbatim (endsWith(".java") false).
        assertThat(CodeEvidenceAdapter.classFromSource(
                new SourceRef("spec:x", "a\\b\\openapi.yaml", null, null, null))).isEqualTo("openapi.yaml");
        // No directory separator at all, non-.java → returned as-is.
        assertThat(CodeEvidenceAdapter.classFromSource(
                new SourceRef("spec:x", "schema.json", null, null, null))).isEqualTo("schema.json");
        // No directory separator, .java → extension stripped.
        assertThat(CodeEvidenceAdapter.classFromSource(
                new SourceRef("code", "Bare.java", null, null, null))).isEqualTo("Bare");
        // Backslash path, .java → basename with extension stripped.
        assertThat(CodeEvidenceAdapter.classFromSource(
                new SourceRef("code", "win\\dir\\Deep.java", null, null, null))).isEqualTo("Deep");
    }

    // ----- end-to-end ordering: endpoints, then DTOs, then caveats; counts consistent -----

    @Test
    void unitsAreEmittedEndpointsThenDtosThenCaveatsWithFetchedOne() {
        SourceRef src = SourceRef.code("src/AccountController.java", 3, 8, null);
        Endpoint ep = new Endpoint(HttpMethod.GET, "/accounts", "list", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), src, "AccountController");
        FieldModel f = new FieldModel("iban", "string", null, true, null, null, src);
        SchemaModel schema = new SchemaModel("Account", "object", List.of(f), List.of(), src);
        ApiModel model = new ApiModel("code", "svc", "1", null, List.of(ep),
                Map.of("Account", schema), List.of("Generated mappers not analysed."));

        SourceExtraction r = adapter.extract(model);
        assertThat(r.units()).hasSize(3);
        assertThat(r.units().get(0).type()).isEqualTo(UnitType.ENDPOINT);
        assertThat(r.units().get(1).type()).isEqualTo(UnitType.DTO_CONSTRAINT);
        assertThat(r.units().get(2).type()).isEqualTo(UnitType.GLOBAL_CAVEAT);
        assertThat(r.kind()).isEqualTo(SourceKind.CODE);
        assertThat(r.requested()).isEqualTo(1);
        assertThat(r.fetched()).isEqualTo(1);
        assertThat(r.redactions()).isZero();   // nothing sensitive in this fixture
    }
}