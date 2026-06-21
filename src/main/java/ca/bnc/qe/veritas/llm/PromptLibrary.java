package ca.bnc.qe.veritas.llm;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Loads a vendored ISTQB prompt from {@code classpath:veritas/prompts/}, injects only the knowledge-pack
 * sections it needs (via {@link KnowledgePackSlicer} — the token saver) at the prompt's
 * {@code ## [KNOWLEDGE PACK]} marker, and substitutes {@code {{var}}} placeholders.
 */
@Component
public class PromptLibrary {

    private static final String PACK_MARKER = "## [KNOWLEDGE PACK]";

    private final ResourceLoader resourceLoader;
    private final KnowledgePackSlicer slicer;

    public PromptLibrary(ResourceLoader resourceLoader, KnowledgePackSlicer slicer) {
        this.resourceLoader = resourceLoader;
        this.slicer = slicer;
    }

    public String load(String promptFile) {
        Resource resource = resourceLoader.getResource("classpath:veritas/prompts/" + promptFile);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load prompt '" + promptFile + "': " + e.getMessage(), e);
        }
    }

    public String assemble(String promptFile, Set<String> packSections, Map<String, String> vars) {
        String prompt = injectPack(load(promptFile), slicer.slice(load("istqb-knowledge-pack.md"), packSections));
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                prompt = prompt.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return prompt;
    }

    private String injectPack(String prompt, String slicedPack) {
        int marker = prompt.indexOf(PACK_MARKER);
        String head = marker >= 0 ? prompt.substring(0, marker) : prompt + "\n\n";
        return head + "## Knowledge pack\n\n" + slicedPack + "\n";
    }
}
