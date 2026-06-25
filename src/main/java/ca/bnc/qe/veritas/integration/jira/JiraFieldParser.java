package ca.bnc.qe.veritas.integration.jira;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses the widened Jira issue fields shared by the v2 (Server/DC) and v3 (Cloud) REST APIs — status, resolution,
 * priority, labels, components, issue links — into the normalized shape the evidence pipeline consumes. The JSON
 * shape of these core fields is identical across editions, so both clients parse them the same way. Every method
 * tolerates a missing field (returns null / an empty list), so it is safe to call on a sparse {@code fields} node.
 */
@Slf4j
public final class JiraFieldParser {

    private JiraFieldParser() {
    }

    /** Resolutions that mean "we decided not to build this" → the issue is descoped (excluded from scope, §1.2). */
    private static final Set<String> DESCOPED_RESOLUTIONS =
            Set.of("won't do", "wont do", "won't fix", "wont fix", "rejected", "declined", "abandoned");

    /**
     * Normalized lifecycle token: {@code DESCOPED} when the resolution is a won't-do/rejected one (regardless of
     * status category), else {@code DONE} / {@code IN_PROGRESS} / {@code TO_DO} from the <b>stable</b>
     * status-category key, else {@code null} when no status was fetched (so callers make no lifecycle claim).
     */
    public static String lifecycle(JsonNode fields) {
        JsonNode resolution = fields.path("resolution");
        if (resolution.isObject()) {
            String res = resolution.path("name").asText("").toLowerCase(Locale.ROOT).trim();
            if (DESCOPED_RESOLUTIONS.contains(res)) {
                return "DESCOPED";
            }
        }
        JsonNode status = fields.path("status");
        if (!status.isObject()) {
            return null;
        }
        String category = status.path("statusCategory").path("key").asText("");
        return switch (category) {
            case "done" -> "DONE";
            case "indeterminate" -> "IN_PROGRESS";
            case "new" -> "TO_DO";
            default -> {
                // Atlassian guarantees new/indeterminate/done; an unexpected (custom workflow) category is observable
                // rather than silently treated as "no status" — the engine then falls back to the source-presence axis.
                if (!category.isEmpty()) {
                    log.debug("Unmapped Jira status category key '{}'; treating lifecycle as unknown", category);
                }
                yield null;
            }
        };
    }

    /** Priority display name, or null when none. */
    public static String priority(JsonNode fields) {
        JsonNode p = fields.path("priority");
        if (!p.isObject()) {
            return null;
        }
        String name = p.path("name").asText("").trim();
        return name.isEmpty() ? null : name;
    }

    /** Labels (verbatim, trimmed, non-blank). */
    public static List<String> labels(JsonNode fields) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : fields.path("labels")) {
            String s = l.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Component names. */
    public static List<String> components(JsonNode fields) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : fields.path("components")) {
            String s = c.path("name").asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Related issue keys from issue links (blocks/relates/etc.), inward or outward — for traceability + status. */
    public static List<String> links(JsonNode fields) {
        List<String> out = new ArrayList<>();
        for (JsonNode link : fields.path("issuelinks")) {
            JsonNode outward = link.path("outwardIssue");
            JsonNode inward = link.path("inwardIssue");
            String key = outward.isObject() ? outward.path("key").asText("")
                    : inward.isObject() ? inward.path("key").asText("") : "";
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return out;
    }
}
