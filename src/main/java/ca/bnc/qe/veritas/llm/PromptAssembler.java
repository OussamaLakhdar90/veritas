package ca.bnc.qe.veritas.llm;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import ca.bnc.qe.veritas.skill.Step;
import ca.bnc.qe.veritas.skill.StepContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Builds the LLM prompt for an LLM step: loads the prompt-skill template
 * ({@code classpath:veritas/prompts/<file>}) and substitutes {@code {{var}}} markers from the run's
 * inputs and the outputs named in the step's {@code inputsFrom}. The LLM only ever sees this assembled
 * text — it never re-parses the repo.
 */
@Component
public class PromptAssembler {

    private final ResourceLoader resourceLoader;

    public PromptAssembler(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String assemble(Step step, StepContext ctx) {
        String template = load(step.promptSkill());
        Map<String, Object> vars = new HashMap<>(ctx.inputs());
        if (step.inputsFrom() != null) {
            for (String name : step.inputsFrom()) {
                vars.put(name, ctx.value(name));
            }
        }
        String result = template;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String token = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            result = result.replace(token, value);
        }
        return result;
    }

    private String load(String promptFile) {
        Resource resource = resourceLoader.getResource("classpath:veritas/prompts/" + promptFile);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load prompt '" + promptFile + "': " + e.getMessage(), e);
        }
    }
}
