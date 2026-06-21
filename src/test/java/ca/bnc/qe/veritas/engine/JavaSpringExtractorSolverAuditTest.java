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
    void unresolvedExtendedBaseRecordsBlindSpot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("OrphanCtrl.java"),
                HDR + "@RestController class OrphanCtrl extends UnknownBase { @GetMapping(\"/x\") String x(){return null;} }");

        ApiModel m = new JavaSpringExtractor().extract(dir);

        assertThat(m.blindSpots().toString()).contains("UnknownBase");   // not dropped silently
        assertThat(m.endpoints().stream().map(e -> e.pathTemplate())).contains("/x");   // own mapping still found
    }
}
