package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.Scope;
import ca.bnc.qe.veritas.codegen.ServiceAuthSpec.ServiceAuthGroup;
import ca.bnc.qe.veritas.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Proves the declared token groups actually reach the [IMPLEMENT-TESTS] prompt the LLM receives. */
@SpringBootTest
class CodegenServiceAuthTest {

    @Autowired
    private CodegenService codegenService;

    @MockBean
    private LlmGateway llm;   // capture the composed prompt

    @Test
    void serviceAuthSpecReachesTheCodegenPrompt() throws Exception {
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
        Path outputDir = Files.createTempDirectory("veritas-auth-out-");

        ServiceAuthSpec spec = new ServiceAuthSpec(List.of(
                new ServiceAuthGroup("tpps", "https://okta/tpps/v1/token", "0oaTPPS", "TPPS_PRIVATE_KEY",
                        "oktaCredentials.json", List.of(new Scope("WRITE", "tpps:write")), List.of("/tpps")),
                new ServiceAuthGroup("apps", "https://okta/apps/v1/token", "0oaAPPS", "APPS_PRIVATE_KEY",
                        "oktaCredentials.json", List.of(new Scope("READ", "apps:read")), List.of("/apps"))));

        codegenService.generate("ciam-policies", serviceRepo, template, outputDir, "tester", Set.of(), spec);

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).complete(prompts.capture(), anyString());
        String implementPrompt = prompts.getAllValues().stream()
                .filter(p -> p.contains("[IMPLEMENT-TESTS]"))
                .findFirst().orElseThrow(() -> new AssertionError("no [IMPLEMENT-TESTS] prompt was sent"));

        assertThat(implementPrompt)
                .contains("SERVICE_AUTH_SPEC").contains("WorldKey.ROBOT_TOKEN_TPPS").contains("WorldKey.ROBOT_TOKEN_APPS")
                .contains("RobotToken").contains("0oaTPPS").contains("apps:read").contains("/tpps").contains("/apps");
    }
}
