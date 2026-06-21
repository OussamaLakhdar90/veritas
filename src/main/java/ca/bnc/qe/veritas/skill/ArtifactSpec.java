package ca.bnc.qe.veritas.skill;

/** Declares an artifact a skill renders at the end (report, corrected YAML file, etc.). */
public record ArtifactSpec(
        String type,
        String renderer,
        String from,
        String path
) {}
