package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the codegen safe-writer: secret scan (#15) + JSON merge / no-clobber (#14). */
class GeneratedFileWriterTest {

    private final GeneratedFileWriter writer = new GeneratedFileWriter(new ObjectMapper());

    @Test
    void rejectsLiteralSecret(@TempDir Path dir) {
        assertThatThrownBy(() -> writer.write(dir.resolve("serverConfig.json"), "serverConfig.json",
                "{\"adminPassword\": \"S3cr3tP@ssw0rd123\"}"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("literal secret");
    }

    @Test
    void rejectsPrivateKey(@TempDir Path dir) {
        assertThatThrownBy(() -> writer.write(dir.resolve("key.pem"), "key.pem",
                "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(PreconditionException.class);
    }

    @Test
    void rejectsProhibitedTool(@TempDir Path dir) {
        assertThatThrownBy(() -> writer.write(dir.resolve("README.md"), "README.md",
                "Run the collection with `newman run policies.postman_collection.json`."))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("prohibited tool");
    }

    @Test
    void allowsSensitiveReference(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("data-manager.json");
        assertThatCode(() -> writer.write(f, "data-manager.json",
                "{\"password\": \"$sensitive:DB_PW\"}")).doesNotThrowAnyException();
        assertThat(Files.readString(f)).contains("$sensitive:DB_PW");
    }

    @Test
    void mergesJsonObjectKeysInsteadOfClobbering(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("data-manager.json");
        Files.writeString(f, "{\"TEST-1\": {\"id\": 1}}");
        writer.write(f, "data-manager.json", "{\"TEST-2\": {\"id\": 2}}");
        String merged = Files.readString(f);
        assertThat(merged).contains("TEST-1").contains("TEST-2");   // old entry preserved, new appended
    }

    @Test
    void deepMergeOnKeyCollisionDoesNotClobberExistingData(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("data-manager.json");
        Files.writeString(f, "{\"policies\": {\"P-1\": {\"id\": 1}}, \"ids\": [\"a\"]}");
        // Same top-level keys, different nested content — prior data must survive (no-clobber, #14).
        writer.write(f, "data-manager.json", "{\"policies\": {\"P-2\": {\"id\": 2}}, \"ids\": [\"b\"]}");
        String merged = Files.readString(f);
        assertThat(merged).contains("P-1").contains("P-2");   // nested object deep-merged, not overwritten
        assertThat(merged).contains("\"a\"").contains("\"b\"");   // nested array appended, not replaced
    }

    @Test
    void mergesJsonArraysWithDedup(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ids.json");
        Files.writeString(f, "[\"x\",\"y\"]");
        writer.write(f, "ids.json", "[\"y\",\"z\"]");
        String merged = Files.readString(f).replaceAll("\\s+", "");
        assertThat(merged).contains("x").contains("y").contains("z");
        // "y" must appear once (deduped)
        int count = merged.split("\"y\"", -1).length - 1;
        assertThat(count).isEqualTo(1);
    }

    @Test
    void overwritesNonJsonFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("FooTest.java");
        Files.writeString(f, "old");
        writer.write(f, "FooTest.java", "new content");
        assertThat(Files.readString(f)).isEqualTo("new content");
    }

    // ---- path-traversal containment (writeWithin) ----

    @Test
    void writeWithinWritesNestedRelativePathInsideBaseDir(@TempDir Path dir) throws Exception {
        writer.writeWithin(dir, "src/api/PolicyTest.java", "class PolicyTest {}");
        Path written = dir.resolve("src/api/PolicyTest.java");
        assertThat(Files.readString(written)).isEqualTo("class PolicyTest {}");
        assertThat(written.normalize().startsWith(dir.normalize())).isTrue();
    }

    @Test
    void writeWithinRejectsParentTraversal(@TempDir Path dir) {
        assertThatThrownBy(() -> writer.writeWithin(dir, "../../../../etc/cron.d/pwn", "* * * * * root sh"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("escapes the output directory");
    }

    @Test
    void writeWithinRejectsAbsolutePath(@TempDir Path dir) {
        String absolute = dir.getRoot().resolve("evil.txt").toString();   // platform-correct absolute path
        assertThatThrownBy(() -> writer.writeWithin(dir, absolute, "x"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("absolute path");
    }

    @Test
    void writeWithinRejectsEmptyPath(@TempDir Path dir) {
        assertThatThrownBy(() -> writer.writeWithin(dir, "  ", "x"))
                .isInstanceOf(PreconditionException.class)
                .hasMessageContaining("empty path");
    }

    @Test
    void writeWithinStillEnforcesSecretScan(@TempDir Path dir) {
        // containment is layered BEFORE the existing secret scan — a contained-but-secret file is still rejected.
        assertThatThrownBy(() -> writer.writeWithin(dir, "config.json", "{\"apiKey\": \"AKIAIOSFODNN7EXAMPLE\"}"))
                .isInstanceOf(PreconditionException.class);
    }
}
