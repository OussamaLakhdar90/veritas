package ca.bnc.qe.veritas.skill;

import java.util.List;
import java.util.Map;

/**
 * A skill = an ordered pipeline of steps. Parsed from {@code resources/skills/<name>.skill.yaml} at startup
 * and validated fail-fast. This is the mechanism behind "a skill per activity".
 */
public record SkillManifest(
        String name,
        String description,
        String istqbSyllabus,
        List<InputSpec> inputs,
        List<String> tokens,
        Map<String, String> context,
        List<Step> pipeline,
        List<ArtifactSpec> artifacts
) {}
