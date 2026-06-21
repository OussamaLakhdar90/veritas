package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TemplateLearnerTest {

    private final TemplateLearner learner = new TemplateLearner();

    @Test
    void parsesFrontMatterAndBody() throws Exception {
        String md = """
            ---
            framework:
              name: ca.bnc.ciam:autotests
              language: java
            buildTool: maven
            verifyCommand: "mvn -q compile test-compile"
            packageRoot: "{svc}Api"
            layout:
              baseTests: src/test/java/base
            ---
            # Conventions
            Two-tier base + happy-path.
            """;
        Path p = Files.createTempFile("tmpl", ".md");
        Files.writeString(p, md);

        TemplateSpec spec = learner.learn(p);

        assertThat(spec.frameworkName()).isEqualTo("ca.bnc.ciam:autotests");
        assertThat(spec.language()).isEqualTo("java");
        assertThat(spec.buildTool()).isEqualTo("maven");
        assertThat(spec.verifyCommand()).contains("mvn");
        assertThat(spec.layout()).containsEntry("baseTests", "src/test/java/base");
        assertThat(spec.body()).contains("Conventions");
    }

    @Test
    void rejectsTemplateWithoutFrontMatter() throws Exception {
        Path p = Files.createTempFile("tmpl", ".md");
        Files.writeString(p, "# Just a body, no front-matter\n");
        assertThatThrownBy(() -> learner.learn(p)).isInstanceOf(IllegalArgumentException.class);
    }
}
