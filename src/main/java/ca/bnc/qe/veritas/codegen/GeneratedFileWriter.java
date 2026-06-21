package ca.bnc.qe.veritas.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ca.bnc.qe.veritas.preflight.PreconditionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Writes generated test/data files safely (Pillar C, plan blind spots #14/#15):
 * <ul>
 *   <li><b>#15 secret scan</b> — refuse to write a file that contains an apparent literal secret; only
 *       {@code $sensitive:ENV}-style references are allowed.</li>
 *   <li><b>#14 merge, don't clobber</b> — for shared JSON registries (e.g. {@code data-manager.json}) merge
 *       into an existing file (objects: union of keys; arrays: append unique) instead of overwriting it.</li>
 * </ul>
 */
@Component
public class GeneratedFileWriter {

    // Matches common literal-secret shapes; $sensitive:ENV refs do not match (their value starts with '$').
    private static final Pattern LITERAL_SECRET = Pattern.compile(
            "(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|client[_-]?secret|bearer)[\"']?\\s*[:=]\\s*"
                    + "[\"']?(?!\\$)([^\\s\"']{8,})"   // value: 8+ non-space/quote chars, NOT a $sensitive:ENV ref
                    + "|-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"
                    + "|AKIA[0-9A-Z]{16}");

    /** Tools prohibited at the bank — generated artifacts must use the approved framework only. */
    private static final Pattern PROHIBITED_TOOL = Pattern.compile("(?i)\\b(postman|newman)\\b");

    private final ObjectMapper mapper;

    public GeneratedFileWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Throws {@link PreconditionException} if {@code content} appears to embed a literal secret. */
    public void assertNoLiteralSecret(String path, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Matcher m = LITERAL_SECRET.matcher(content);
        if (m.find()) {
            String hit = m.group();
            throw new PreconditionException("implement-tests", List.of(
                    "Refusing to write an apparent literal secret into generated file '" + path
                            + "'. Use a $sensitive:ENV reference instead (matched near: \""
                            + hit.substring(0, Math.min(28, hit.length())) + "…\")."));
        }
    }

    /** Reject any generated artifact that references a bank-prohibited tool (Postman/Newman). */
    public void assertNoProhibitedTool(String path, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Matcher m = PROHIBITED_TOOL.matcher(content);
        if (m.find()) {
            throw new PreconditionException("implement-tests", List.of(
                    "Generated file '" + path + "' references the prohibited tool '" + m.group()
                            + "'. Only the approved framework (TestNG + Rest-Assured / ca.bnc.lsist.api) is permitted."));
        }
    }

    /** Scan, then write — merging into an existing JSON registry rather than clobbering it. */
    public void write(Path target, String relPathForMessage, String content) throws IOException {
        assertNoLiteralSecret(relPathForMessage, content);
        assertNoProhibitedTool(relPathForMessage, content);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        if (Files.exists(target) && target.getFileName().toString().toLowerCase().endsWith(".json")) {
            String merged = tryMergeJson(Files.readString(target), content);
            if (merged != null) {
                Files.writeString(target, merged);
                return;
            }
        }
        Files.writeString(target, content);
    }

    /** Returns merged JSON text, or null when the two aren't both mergeable JSON of the same shape. */
    String tryMergeJson(String existing, String incoming) {
        try {
            JsonNode a = mapper.readTree(existing);
            JsonNode b = mapper.readTree(incoming);
            if (a.isObject() && b.isObject()) {
                ObjectNode merged = ((ObjectNode) a).deepCopy();
                b.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));   // incoming wins per key, old kept
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(merged);
            }
            if (a.isArray() && b.isArray()) {
                ArrayNode merged = ((ArrayNode) a).deepCopy();
                Set<String> seen = new HashSet<>();
                a.forEach(n -> seen.add(n.toString()));
                for (JsonNode n : b) {
                    if (seen.add(n.toString())) {
                        merged.add(n);
                    }
                }
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(merged);
            }
        } catch (Exception ignore) {
            // not mergeable JSON — caller overwrites
        }
        return null;
    }
}
