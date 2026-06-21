package ca.bnc.qe.veritas.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Component;

/**
 * Parses the user's MD test-generation template: YAML front-matter (machine contract — framework, layout,
 * verify command) + markdown body (the pattern the LLM must mirror). Fails fast if the template is missing
 * or lacks front-matter — Veritas never guesses a framework.
 */
@Component
public class TemplateLearner {

    private final ObjectMapper yaml = new YAMLMapper();

    @SuppressWarnings("unchecked")
    public TemplateSpec learn(Path templatePath) {
        String content;
        try {
            content = Files.readString(templatePath);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read template at " + templatePath
                    + " — provide a test-generation template (see docs/test-generation-template.md): " + e.getMessage());
        }
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) {
            throw new IllegalArgumentException("Template " + templatePath
                    + " has no YAML front-matter (required: framework, layout, verifyCommand).");
        }
        int end = trimmed.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalArgumentException("Template " + templatePath + " front-matter is not closed with '---'.");
        }
        String frontMatter = trimmed.substring(3, end);
        String body = trimmed.substring(end + 4).stripLeading();

        Map<String, Object> fm;
        try {
            fm = yaml.readValue(frontMatter, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Template front-matter is not valid YAML: " + e.getMessage(), e);
        }
        Map<String, Object> framework = asMap(fm.get("framework"));
        Map<String, String> layout = asStringMap(fm.get("layout"));
        if (framework.get("name") == null) {
            throw new IllegalArgumentException("Template must declare framework.name (the framework it consumes).");
        }
        return new TemplateSpec(
                str(framework.get("name")), str(framework.get("language")),
                str(fm.get("buildTool")), str(fm.get("verifyCommand")), str(fm.get("packageRoot")),
                layout, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> asStringMap(Object o) {
        if (!(o instanceof Map)) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        ((Map<String, Object>) o).forEach((k, v) -> out.put(k, str(v)));
        return out;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
