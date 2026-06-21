package ca.bnc.qe.veritas.integration.jira;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds a minimal Atlassian Document Format (ADF) doc from plain paragraphs — required by Jira Cloud v3. */
public final class AdfBuilder {

    private static final ObjectMapper M = new ObjectMapper();

    private AdfBuilder() {
    }

    public static JsonNode doc(List<String> paragraphs) {
        ObjectNode doc = M.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");
        if (paragraphs != null) {
            for (String p : paragraphs) {
                ObjectNode para = M.createObjectNode();
                para.put("type", "paragraph");
                ArrayNode pc = para.putArray("content");
                if (p != null && !p.isEmpty()) {
                    ObjectNode text = M.createObjectNode();
                    text.put("type", "text");
                    text.put("text", p);
                    pc.add(text);
                }
                content.add(para);
            }
        }
        return doc;
    }
}
