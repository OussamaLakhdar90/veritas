package ca.bnc.qe.veritas.codegen;

import java.util.Map;

/**
 * The pattern the generator must follow, derived from the user-supplied MD template's front-matter.
 * The framework is declared by the template — Veritas bakes in no framework assumptions.
 */
public record TemplateSpec(
        String frameworkName,
        String language,
        String buildTool,
        String verifyCommand,
        String packageRoot,
        Map<String, String> layout,
        String body
) {}
