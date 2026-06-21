package ca.bnc.qe.veritas.llm;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The token-saving mechanism from the prompt review: instead of pasting the whole ISTQB knowledge-pack into
 * every prompt, slice it to only the sections a prompt cites (plus §0, always). A prompt's
 * {@code KNOWLEDGE-PACK-SECTIONS: 0,1,5,6,12} directive drives which major sections are kept.
 */
@Component
public class KnowledgePackSlicer {

    private static final Pattern HEADING = Pattern.compile("^##\\s+§?(\\d+)");

    public String slice(String pack, Set<String> wantedMajorSections) {
        if (pack == null) {
            return "";
        }
        Set<String> keep = new HashSet<>(wantedMajorSections == null ? Set.of() : wantedMajorSections);
        keep.add("0");   // grounding rules are always included
        StringBuilder out = new StringBuilder();
        boolean including = false;
        for (String line : pack.split("\n", -1)) {
            if (line.startsWith("## ")) {
                Matcher m = HEADING.matcher(line);
                including = m.find() && keep.contains(m.group(1));
            }
            if (including) {
                out.append(line).append("\n");
            }
        }
        return out.toString().strip();
    }
}
