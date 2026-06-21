package ca.bnc.qe.veritas.skill;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Loads every {@code classpath*:skills/*.skill.yaml} at startup, deserializes to {@link SkillManifest},
 * and validates fail-fast: unknown handler beans, missing prompt/schema on LLM steps, duplicate step ids,
 * or {@code inputsFrom} that reference an unknown output all abort boot.
 */
@Component
@Slf4j
public class SkillManifestLoader {

    private final Map<String, SkillManifest> skills = new HashMap<>();
    private final Map<String, StepHandler> handlers;
    private final ApplicationContext applicationContext;
    private final ObjectMapper yaml = YAMLMapper.builder()
            .addModule(new ParameterNamesModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .build();

    public SkillManifestLoader(Map<String, StepHandler> handlers, ApplicationContext applicationContext) {
        this.handlers = handlers;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void load() throws IOException {
        Resource[] resources = applicationContext.getResources("classpath*:skills/*.skill.yaml");
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                SkillManifest manifest = yaml.readValue(in, SkillManifest.class);
                validate(manifest, resource.getFilename());
                skills.put(manifest.name(), manifest);
                log.info("Loaded skill '{}' ({} steps) from {}",
                        manifest.name(), manifest.pipeline().size(), resource.getFilename());
            }
        }
        log.info("Skill manifests loaded: {}", skills.keySet());
    }

    public SkillManifest get(String name) {
        SkillManifest manifest = skills.get(name);
        if (manifest == null) {
            throw new IllegalArgumentException("Unknown skill '" + name + "'. Known: " + skills.keySet());
        }
        return manifest;
    }

    public Map<String, SkillManifest> all() {
        return Map.copyOf(skills);
    }

    private void validate(SkillManifest m, String file) {
        if (m.name() == null || m.name().isBlank()) {
            throw new IllegalStateException("Skill manifest " + file + " has no name");
        }
        if (m.pipeline() == null || m.pipeline().isEmpty()) {
            throw new IllegalStateException("Skill '" + m.name() + "' has an empty pipeline");
        }
        Set<String> seenOut = new HashSet<>();
        Set<String> seenIds = new HashSet<>();
        for (Step step : m.pipeline()) {
            if (step.id() == null || !seenIds.add(step.id())) {
                throw new IllegalStateException("Skill '" + m.name() + "' has a missing/duplicate step id: " + step.id());
            }
            if (step.kind() == null) {
                throw new IllegalStateException("Step '" + step.id() + "' in '" + m.name() + "' has no kind");
            }
            switch (step.kind()) {
                case DETERMINISTIC -> {
                    if (step.handler() == null || !handlers.containsKey(step.handler())) {
                        throw new IllegalStateException("Step '" + step.id() + "' in '" + m.name()
                                + "' references unknown handler bean '" + step.handler()
                                + "'. Known handlers: " + handlers.keySet());
                    }
                }
                case LLM -> {
                    if (step.promptSkill() == null || step.expectsJson() == null) {
                        throw new IllegalStateException("LLM step '" + step.id() + "' in '" + m.name()
                                + "' requires both promptSkill and expectsJson");
                    }
                }
                case GATE -> { /* no extra requirements */ }
            }
            if (step.inputsFrom() != null) {
                for (String ref : step.inputsFrom()) {
                    if (!seenOut.contains(ref)) {
                        throw new IllegalStateException("Step '" + step.id() + "' in '" + m.name()
                                + "' reads unknown output '" + ref + "' (not produced by an earlier step)");
                    }
                }
            }
            if (step.out() != null) {
                seenOut.add(step.out());
            }
        }
    }
}
