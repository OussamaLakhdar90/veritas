package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CodegenServiceTest {

    @Autowired
    private CodegenService codegenService;

    @Test
    void generatesFilesFromTemplate() throws Exception {
        Path template = Files.createTempFile("tmpl", ".md");
        Files.writeString(template, """
            ---
            framework:
              name: demo-framework
              language: java
            buildTool: maven
            verifyCommand: ""
            layout: {}
            ---
            body
            """);
        Path serviceRepo = Path.of(getClass().getClassLoader().getResource("fixtures/policies").toURI());
        Path outputDir = Files.createTempDirectory("veritas-out-");

        CodegenRun run = codegenService.generate("ciam-policies", serviceRepo, template, outputDir, "tester");

        assertThat(run.getId()).isNotBlank();
        assertThat(run.getBuildStatus()).isEqualTo("SKIPPED");
        assertThat(Files.exists(outputDir.resolve("src/test/java/GeneratedApiTest.java"))).isTrue();
        // Distinct data-gen step ran first: data artifacts present, secrets only as $sensitive refs (never literal).
        Path serverConfig = outputDir.resolve("src/test/resources/serverConfig.json");
        assertThat(Files.exists(serverConfig)).isTrue();
        assertThat(Files.readString(serverConfig)).contains("$sensitive:");
        // Both steps' TODOs merged onto the run.
        assertThat(run.getTodos()).contains("Provision a seed policy id").contains("Set the base URL");
    }

    @Test
    void attemptsBoundedRepairWhenBuildFails() throws Exception {
        Path template = Files.createTempFile("tmpl", ".md");
        Files.writeString(template, """
            ---
            framework:
              name: demo-framework
              language: java
            buildTool: maven
            verifyCommand: "java --veritas-bogus-flag-xyz"
            layout: {}
            ---
            body
            """);
        Path serviceRepo = Path.of(getClass().getClassLoader().getResource("fixtures/policies").toURI());
        Path outputDir = Files.createTempDirectory("veritas-out-fail-");

        CodegenRun run = codegenService.generate("ciam-policies", serviceRepo, template, outputDir, "tester");

        // The always-failing command can't be repaired, so it stays FAIL — but the repair pass DID run.
        assertThat(run.getBuildStatus()).isEqualTo("FAIL");
        assertThat(Files.exists(outputDir.resolve("src/test/java/RepairedApiTest.java"))).isTrue();
    }
}
