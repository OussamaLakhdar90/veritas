package ca.bnc.qe.veritas.contract;

/**
 * Pulls the YAML block for one endpoint path out of a raw OpenAPI/Swagger spec, so a finding can show the
 * "current YAML" fragment in the management report. Deterministic and best-effort: locates the path key
 * under {@code paths:} and captures its indented sub-tree. Returns null if not found (e.g. a JSON spec or a
 * path not present), in which case the report simply omits the fragment.
 */
public final class SpecFragmentExtractor {

    private SpecFragmentExtractor() {
    }

    /** @param endpoint "METHOD /path" (the path after the first space) or a bare "/path". */
    public static String extract(String specText, String endpoint) {
        if (specText == null || endpoint == null || specText.isBlank()) {
            return null;
        }
        String path = endpoint.contains(" ") ? endpoint.substring(endpoint.indexOf(' ') + 1).trim() : endpoint.trim();
        if (path.isEmpty()) {
            return null;
        }
        String[] lines = specText.split("\n", -1);
        int start = -1;
        int keyIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.equals(path + ":") || trimmed.equals("\"" + path + "\":") || trimmed.equals("'" + path + "':")) {
                start = i;
                keyIndent = indentOf(line);
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(lines[start]).append("\n");
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                sb.append(line).append("\n");
                continue;
            }
            if (indentOf(line) <= keyIndent) {
                break;   // next sibling/dedent — end of this path's block
            }
            sb.append(line).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}
