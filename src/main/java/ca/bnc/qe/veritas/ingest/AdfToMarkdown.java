package ca.bnc.qe.veritas.ingest;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Converts Atlassian Document Format (Jira Cloud rich-text JSON) into lean markdown. Strips media/avatars/
 * render-hints, keeps headings/lists/tables/code/marks. Unknown node types recurse into their children
 * rather than being dropped — tolerant to ADF schema drift.
 */
@Component
@Slf4j
public class AdfToMarkdown {

    private final ObjectMapper mapper;

    public AdfToMarkdown(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public NormalizedDoc normalize(String sourceId, String title, String adfJson) {
        try {
            return new NormalizedDoc("jira", sourceId, title, toMarkdown(mapper.readTree(adfJson)));
        } catch (Exception e) {
            log.warn("ADF parse failed for {}: {}", sourceId, e.getMessage());
            return new NormalizedDoc("jira", sourceId, title, "");
        }
    }

    public String toMarkdown(JsonNode doc) {
        return doc == null ? "" : renderBlocks(doc.get("content")).strip();
    }

    private String renderBlocks(JsonNode content) {
        if (content == null || !content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : content) {
            sb.append(renderBlock(n));
        }
        return sb.toString();
    }

    private String renderBlock(JsonNode n) {
        String type = n.path("type").asText("");
        return switch (type) {
            case "heading" -> {
                int lvl = Math.min(6, Math.max(1, n.path("attrs").path("level").asInt(1)));
                yield "#".repeat(lvl) + " " + renderInline(n.get("content")) + "\n\n";
            }
            case "paragraph" -> {
                String p = renderInline(n.get("content"));
                yield p.isBlank() ? "" : p + "\n\n";
            }
            case "bulletList" -> renderList(n.get("content"), false) + "\n";
            case "orderedList" -> renderList(n.get("content"), true) + "\n";
            case "codeBlock" -> "```" + n.path("attrs").path("language").asText("") + "\n"
                    + renderInline(n.get("content")) + "\n```\n\n";
            case "blockquote" -> "> " + renderBlocks(n.get("content")).strip().replace("\n", "\n> ") + "\n\n";
            case "panel" -> renderBlocks(n.get("content"));
            case "rule" -> "---\n\n";
            case "table" -> renderTable(n) + "\n";
            default -> {
                if (n.has("content")) {
                    yield renderBlocks(n.get("content"));
                }
                yield n.has("text") ? renderInline(n) + "\n\n" : "";
            }
        };
    }

    private String renderList(JsonNode items, boolean ordered) {
        if (items == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode li : items) {
            String marker = ordered ? (i++) + ". " : "- ";
            String text = renderBlocks(li.get("content")).strip().replace("\n\n", "\n");
            sb.append(marker).append(text).append("\n");
        }
        return sb.toString();
    }

    private String renderInline(JsonNode content) {
        if (content == null) {
            return "";
        }
        if (content.isObject()) {
            return renderInlineNode(content);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : content) {
            sb.append(renderInlineNode(n));
        }
        return sb.toString();
    }

    private String renderInlineNode(JsonNode n) {
        String type = n.path("type").asText("");
        switch (type) {
            case "text" -> {
                String t = n.path("text").asText("");
                for (JsonNode mark : n.path("marks")) {
                    t = switch (mark.path("type").asText("")) {
                        case "strong" -> "**" + t + "**";
                        case "em" -> "*" + t + "*";
                        case "code" -> "`" + t + "`";
                        case "link" -> "[" + t + "](" + mark.path("attrs").path("href").asText("") + ")";
                        default -> t;
                    };
                }
                return t;
            }
            case "hardBreak" -> {
                return "  \n";
            }
            case "mention" -> {
                return "@" + n.path("attrs").path("text").asText(n.path("attrs").path("id").asText(""));
            }
            case "emoji" -> {
                return n.path("attrs").path("text").asText("");
            }
            default -> {
                if (n.has("content")) {
                    return renderInline(n.get("content"));
                }
                return n.has("text") ? n.path("text").asText("") : "";
            }
        }
    }

    private String renderTable(JsonNode table) {
        JsonNode rows = table.get("content");
        if (rows == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (JsonNode row : rows) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row.path("content")) {
                cells.add(renderBlocks(cell.get("content")).strip().replace("\n", " "));
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (first) {
                sb.append("|").append(" --- |".repeat(cells.size())).append("\n");
                first = false;
            }
        }
        return sb.toString();
    }
}
