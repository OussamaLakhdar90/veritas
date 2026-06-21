package ca.bnc.qe.veritas.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;

/** The bundled BNC autotests template (the default templateSource) must exist and parse via TemplateLearner. */
class DefaultTemplateTest {

    @Test
    void bundledDefaultTemplateIsValidAndLearnable() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(CodegenService.DEFAULT_TEMPLATE_RESOURCE)) {
            assertThat(in).as("bundled default template on classpath").isNotNull();
            Path tmp = Files.createTempFile("veritas-tmpl", ".md");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);

            TemplateSpec spec = new TemplateLearner().learn(tmp);
            assertThat(spec.frameworkName()).contains("TestNG");
            assertThat(spec.language()).isEqualTo("java");
            assertThat(spec.verifyCommand()).contains("test-compile");
            // Body carries the two-tier rule + $sensitive enforcement + suite XML the LLM must mirror.
            assertThat(spec.body())
                    .contains("Base tests vs Validation tests")
                    .contains("$sensitive")
                    .contains("Suite XML");
        }
    }
}
