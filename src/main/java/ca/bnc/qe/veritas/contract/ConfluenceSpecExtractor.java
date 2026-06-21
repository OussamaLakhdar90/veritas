package ca.bnc.qe.veritas.contract;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/**
 * Pulls an OpenAPI/Swagger spec out of a Confluence page's storage-format XHTML. Specs are stored in code
 * macros ({@code <ac:plain-text-body><![CDATA[ … ]]></ac:plain-text-body>}) or {@code <pre>}/{@code <code>}
 * blocks. Whitespace is preserved verbatim (YAML is whitespace-significant); the block that most looks like
 * a spec wins, with length as the tie-breaker.
 */
public final class ConfluenceSpecExtractor {

    private static final String[] CODE_TAGS = {"ac:plain-text-body", "pre", "code"};

    private ConfluenceSpecExtractor() {
    }

    public static String extractSpec(String storageXhtml) {
        if (storageXhtml == null || storageXhtml.isBlank()) {
            throw new IllegalStateException("Confluence page body is empty — no spec to extract");
        }
        Document doc = Jsoup.parse(storageXhtml, "", Parser.xmlParser());
        List<String> blocks = new ArrayList<>();
        for (String tag : CODE_TAGS) {
            for (Element el : doc.getElementsByTag(tag)) {
                String text = wholeText(el);
                if (!text.isBlank()) {
                    blocks.add(text);
                }
            }
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String block : blocks) {
            int score = score(block);
            if (score > bestScore || (score == bestScore && best != null && block.length() > best.length())) {
                best = block;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No code block found on the Confluence page to extract a spec from");
        }
        return best.strip() + "\n";
    }

    /** Concatenates direct text + CDATA children preserving original whitespace. */
    private static String wholeText(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node n : el.childNodes()) {
            if (n instanceof CDataNode c) {
                sb.append(c.getWholeText());
            } else if (n instanceof TextNode t) {
                sb.append(t.getWholeText());
            }
        }
        return sb.toString();
    }

    /** Higher = more spec-like. */
    private static int score(String block) {
        String lower = block.toLowerCase();
        int score = 0;
        if (lower.contains("openapi")) {
            score += 3;
        }
        if (lower.contains("swagger")) {
            score += 2;
        }
        if (lower.contains("paths:")) {
            score += 2;
        }
        if (lower.contains("\"paths\"")) {
            score += 2;
        }
        return score;
    }
}
