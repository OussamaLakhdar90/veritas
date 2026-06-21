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
}
