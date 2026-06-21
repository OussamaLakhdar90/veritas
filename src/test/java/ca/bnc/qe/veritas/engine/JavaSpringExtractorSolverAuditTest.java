package ca.bnc.qe.veritas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the fixes from the SymbolSolver audit (workflow wf_9ce71ab6-3de): enums become string schemas with
 * values (not empty {type:object}), and request mappings inherited from an extended base class are emitted
 * (with an honest blind spot when the base is outside the scanned sources).
 */
class JavaSpringExtractorSolverAuditTest {

    private static final String HDR = "package demo;\nimport org.springframework.web.bind.annotation.*;\n";

    @Test
    void enumTypeBecomesStringSchemaWithValues(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Status.java"), "package demo; public enum Status { ACTIVE, CLOSED }");
        Files.writeString(dir.resolve("StatusCtrl.java"),
                HDR + "@RestController class StatusCtrl { @GetMapping(\"/s\") Status get(){return null;} }");

        SchemaModel s = new JavaSpringExtractor().extract(dir).schemas().get("Status");

        assertThat(s).isNotNull();
        assertThat(s.type()).isEqualTo("string");                 // not "object"
        assertThat(s.enumValues()).containsExactly("ACTIVE", "CLOSED");
    }

    @Test
    void enumFieldBecomesInlineStringEnumNotPhantomObject(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Status.java"), "package demo; public enum Status { ACTIVE, CLOSED }");
        Files.writeString(dir.resolve("Account.java"),
                "package demo; public class Account { public Status status; public String id; }");
        Files.writeString(dir.resolve("AccountCtrl.java"),
                HDR + "@RestController class AccountCtrl { @GetMapping(\"/a\") Account get(){return null;} }");

        SchemaModel acct = new JavaSpringExtractor().extract(dir).schemas().get("Account");
        assertThat(acct).isNotNull();
        FieldModel status = acct.fields().stream().filter(f -> f.jsonName().equals("status")).findFirst().orElseThrow();

        assertThat(status.type()).isEqualTo("string");            // not "object"
        assertThat(status.refSchema()).isNull();                  // not a phantom object ref
        assertThat(status.constraints().enumValues()).containsExactly("ACTIVE", "CLOSED");
    }

    @Test
    void inheritedControllerMappingsAreEmitted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("BaseCtrl.java"),
                HDR + "abstract class BaseCtrl { @GetMapping(\"/base/ping\") String ping(){return null;} }");
        Files.writeString(dir.resolve("ChildCtrl.java"),
                HDR + "@RestController class ChildCtrl extends BaseCtrl { @GetMapping(\"/child\") String c(){return null;} }");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(m.endpoints().stream().map(e -> e.pathTemplate())).contains("/child", "/base/ping");
    }

    @Test
    void subclassOverrideWinsOverInheritedMapping(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("BaseCtrl.java"),
                HDR + "abstract class BaseCtrl { @GetMapping(\"/base/old\") String ping(){return null;} }");
        Files.writeString(dir.resolve("ChildCtrl.java"),
                HDR + "@RestController class ChildCtrl extends BaseCtrl { @GetMapping(\"/base/new\") String ping(){return null;} }");

        var paths = new JavaSpringExtractor().extract(dir).endpoints().stream().map(e -> e.pathTemplate()).toList();

        assertThat(paths).contains("/base/new").doesNotContain("/base/old");
    }

    @Test
    void composedParamBindingAnnotationIsNotDropped(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("TenantId.java"),
                "package demo; import org.springframework.web.bind.annotation.RequestParam;\n"
                        + "@RequestParam @interface TenantId {}");
        Files.writeString(dir.resolve("TenantCtrl.java"),
                HDR + "@RestController class TenantCtrl { @GetMapping(\"/t\") String t(@TenantId String tid){return null;} }");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        var params = m.endpoints().get(0).params();
        assertThat(params).isNotEmpty();   // composed @RequestParam binding produces a QUERY param, not dropped
        assertThat(params.stream().anyMatch(p -> p.name().equals("tid"))).isTrue();
    }

    @Test
    void customStereotypeAndClassLevelMetaRequestMappingAreHonored(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ApiV1Controller.java"),
                "package demo; import org.springframework.web.bind.annotation.*;\n"
                        + "@RestController @RequestMapping(\"/api/v1\") @interface ApiV1Controller {}");
        Files.writeString(dir.resolve("V1Ctrl.java"),
                HDR + "@ApiV1Controller class V1Ctrl { @GetMapping(\"/things\") String t(){return null;} }");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        // stereotype recognized as a controller AND its class-level @RequestMapping base path applied
        assertThat(m.endpoints().stream().map(e -> e.pathTemplate())).contains("/api/v1/things");
    }

    @Test
    void customSecurityAnnotationIsHonoredNotReportedUnsecured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("AdminOnly.java"),
                "package demo; import org.springframework.security.access.prepost.PreAuthorize;\n"
                        + "@PreAuthorize(\"hasRole('ADMIN')\") @interface AdminOnly {}");
        Files.writeString(dir.resolve("SecCtrl.java"),
                HDR + "@RestController class SecCtrl { @AdminOnly @GetMapping(\"/admin\") String a(){return null;} }");

        var ep = new JavaSpringExtractor().extract(dir).endpoints().stream()
                .filter(e -> e.pathTemplate().equals("/admin")).findFirst().orElseThrow();

        assertThat(ep.security().toString()).contains("ADMIN");   // composed annotation's @PreAuthorize is surfaced
    }

    @Test
    void collectionFieldBecomesArrayNotObject(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("LineItem.java"), "package demo; public class LineItem { public String sku; }");
        Files.writeString(dir.resolve("Order.java"),
                "package demo; import java.util.List;\npublic class Order { public List<LineItem> items;"
                        + " public List<String> tags; public String[] codes; public String id; }");
        Files.writeString(dir.resolve("OrderCtrl.java"),
                HDR + "@RestController class OrderCtrl { @GetMapping(\"/o\") Order get(){return null;} }");

        SchemaModel order = new JavaSpringExtractor().extract(dir).schemas().get("Order");
        assertThat(order).isNotNull();

        assertThat(field(order, "items").type()).isEqualTo("array");
        assertThat(field(order, "items").refSchema()).isEqualTo("LineItem[]");   // DTO element captured
        assertThat(field(order, "tags").type()).isEqualTo("array");
        assertThat(field(order, "tags").refSchema()).isNull();                   // scalar element → bare array
        assertThat(field(order, "codes").type()).isEqualTo("array");             // Java array String[]
        assertThat(field(order, "id").type()).isEqualTo("string");              // non-collection field unaffected
    }

    private static FieldModel field(SchemaModel s, String name) {
        return s.fields().stream().filter(f -> f.jsonName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void wildcardGenericRecoversBoundType(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Foo.java"), "package demo; public class Foo { public String id; }");
        Files.writeString(dir.resolve("WildCtrl.java"),
                HDR + "import java.util.List;\nimport org.springframework.http.ResponseEntity;\n"
                        + "@RestController class WildCtrl { @GetMapping(\"/w\") ResponseEntity<List<? extends Foo>> g(){return null;} }");

        ApiModel m = new JavaSpringExtractor().extract(dir);
        // schemaRef is the modelled type (not the source snippet); the wildcard bound Foo is recovered, array kept.
        assertThat(m.endpoints().get(0).responses().get(0).schemaRef()).isEqualTo("Foo[]");
        assertThat(m.schemas()).containsKey("Foo");                 // inner type built, not lost
        assertThat(m.blindSpots().toString()).doesNotContain("?");  // no literal wildcard flagged as a blind spot
    }

    @Test
    void mapBodyIsFreeFormNotAPhantomSchemaRef(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Foo.java"), "package demo; public class Foo { public String id; }");
        Files.writeString(dir.resolve("MapCtrl.java"),
                HDR + "import java.util.Map;\n"
                        + "@RestController class MapCtrl { @GetMapping(\"/m\") Map<String, Foo> g(){return null;} }");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(m.schemas()).doesNotContainKey("Map");
        assertThat(m.endpoints().get(0).responses().get(0).schemaRef()).isNull();   // free-form, no phantom 'Map' ref
        assertThat(m.blindSpots().toString()).doesNotContain("Map");
    }

    @Test
    void unresolvedExtendedBaseRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("OrphanCtrl.java"),
                HDR + "@RestController class OrphanCtrl extends UnknownBase { @GetMapping(\"/x\") String x(){return null;} }");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(m.blindSpots().toString()).contains("UnknownBase");   // not dropped silently
        assertThat(m.endpoints().stream().map(e -> e.pathTemplate())).contains("/x");   // own mapping still found
    }
}
