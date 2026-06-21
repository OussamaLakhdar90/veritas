package ca.bnc.qe.veritas.engine.model;

/**
 * Where a model element came from — the evidence behind a finding.
 * For code: {@code location} is a file path, with line range + snippet.
 * For a spec: {@code location} is a JSON-pointer, with the serialized fragment as the snippet.
 */
public record SourceRef(
        String origin,        // "code" | "spec:<id>"
        String location,      // file path | JSON pointer
        Integer startLine,
        Integer endLine,
        String snippet
) {
    public static SourceRef code(String file, Integer start, Integer end, String snippet) {
        return new SourceRef("code", file, start, end, snippet);
    }

    public static SourceRef spec(String specId, String pointer, String snippet) {
        return new SourceRef("spec:" + specId, pointer, null, null, snippet);
    }
}
