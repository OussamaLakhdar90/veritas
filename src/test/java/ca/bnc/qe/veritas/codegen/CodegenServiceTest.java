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
    }
}
