package ca.bnc.qe.veritas.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pulls the machine-readable JSON out of an LLM reply. Prompts emit human markdown first and a single
 * fenced {@code ```json} block last, so we take the LAST fenced json block; if none is present we fall
 * back to the outermost balanced braces.
 */
@Component
public class JsonBlockExtractor {

    private static final Pattern FENCED = Pattern.compile("```json\\s*(.*?)```", Pattern.DOTALL);

    public String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned empty output; no JSON to extract");
        }
        Matcher m = FENCED.matcher(raw);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if (last != null && !last.isBlank()) {
            return last.trim();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1).trim();
        }
        int arrStart = raw.indexOf('[');
        int arrEnd = raw.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return raw.substring(arrStart, arrEnd + 1).trim();
        }
        throw new IllegalStateException("No JSON block found in LLM output");
    }
}
