package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/** When veritas.codegen.framework-source-dir is set, the real FRAMEWORK_API evidence reaches the codegen prompt. */
@SpringBootTest
@TestPropertySource(properties = "veritas.codegen.framework-source-dir=src/test/resources/fixtures/framework")
class CodegenServiceFrameworkApiTest {

    @Autowired
    private CodegenService codegenService;

    @MockBean
    private LlmGateway llm;

    @Test
    void frameworkApiBlockReachesTheCodegenPrompt() throws Exception {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.complete(anyString(), anyString()))
                .thenReturn("ok\n```json\n{\"files\":[],\"todos\":[]}\n```\n");

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
        Path outputDir = Files.createTempDirectory("veritas-fwapi-");

        codegenService.generate("ciam-policies", serviceRepo, template, outputDir, "tester");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).complete(prompts.capture(), anyString());
        String implementPrompt = prompts.getAllValues().stream()
                .filter(p -> p.contains("[IMPLEMENT-TESTS]"))
                .findFirst().orElseThrow(() -> new AssertionError("no [IMPLEMENT-TESTS] prompt was sent"));

        assertThat(implementPrompt)
                .contains("FRAMEWORK_API").contains("WorldKey constants")
                .contains("ROBOT_TOKEN").contains("Response get(String, String, String)");
    }
}
