package ca.bnc.qe.veritas.ingest;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * Converts Confluence storage-format XHTML into lean markdown. Keeps headings/lists/tables/code; unwraps
 * useful macros (code/info/note/panel/expand → content; jira → issue key); drops layout/TOC noise.
 * Uses jsoup's XML parser so {@code ac:}/{@code ri:} namespaced elements survive.
 */
@Component
public class ConfluenceStorageToMarkdown {

    public NormalizedDoc normalize(String sourceId, String title, String storageXhtml) {
        var doc = Jsoup.parse(storageXhtml == null ? "" : storageXhtml, "", Parser.xmlParser());
        StringBuilder sb = new StringBuilder();
        for (Node child : doc.childNodes()) {
            sb.append(render(child));
        }
        return new NormalizedDoc("confluence", sourceId, title, sb.toString().strip());
    }

    private String render(Node node) {
        if (node instanceof TextNode tn) {
            String t = tn.text();
            return t.isBlank() ? "" : t;
        }
        if (node instanceof Element e) {
            return renderElement(e);
        }
        return "";
    }

    private String renderElement(Element e) {
        String tag = e.tagName().toLowerCase();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int lvl = Integer.parseInt(tag.substring(1));
                return "#".repeat(lvl) + " " + inline(e).strip() + "\n\n";
            }
            case "p" -> {
                String t = inline(e).strip();
                return t.isEmpty() ? "" : t + "\n\n";
            }
            case "ul" -> {
                StringBuilder s = new StringBuilder();
                for (Element li : e.children()) {
                    s.append("- ").append(inline(li).strip()).append("\n");
                }
                return s.append("\n").toString();
            }
            case "ol" -> {
                StringBuilder s = new StringBuilder();
                int i = 1;
                for (Element li : e.children()) {
                    s.append(i++).append(". ").append(inline(li).strip()).append("\n");
                }
                return s.append("\n").toString();
            }
            case "table" -> {
                return renderTable(e) + "\n";
            }
            case "pre" -> {
                return "```\n" + e.text() + "\n```\n\n";
            }
            case "blockquote" -> {
                return "> " + inline(e).strip().replace("\n", "\n> ") + "\n\n";
            }
            case "ac:structured-macro" -> {
                return renderMacro(e);
            }
            case "ac:layout", "ac:layout-section", "ac:layout-cell", "ac:rich-text-body" -> {
                return renderChildren(e);
            }
            default -> {
                return renderChildren(e);
            }
        }
    }

    private String renderMacro(Element macro) {
        String name = macro.attr("ac:name").toLowerCase();
        if (name.equals("code")) {
            String lang = macroParam(macro, "language");
            Element body = macro.getElementsByTag("ac:plain-text-body").first();
            String code = body != null ? (body.text().isBlank() ? body.wholeText() : body.text()) : "";
            return "```" + lang + "\n" + code.strip() + "\n```\n\n";
        }
        if (name.equals("jira")) {
            String key = macroParam(macro, "key");
            return key.isEmpty() ? "" : "JIRA:" + key + " ";
        }
        // info / note / tip / warning / panel / expand and others → unwrap their rich-text body
        Element rich = macro.getElementsByTag("ac:rich-text-body").first();
        return rich != null ? renderChildren(rich) : renderChildren(macro);
    }

    private String macroParam(Element macro, String paramName) {
        for (Element p : macro.getElementsByTag("ac:parameter")) {
            if (paramName.equalsIgnoreCase(p.attr("ac:name"))) {
                return p.text();
            }
        }
        return "";
    }

    private String renderChildren(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node child : e.childNodes()) {
            sb.append(render(child));
        }
        return sb.toString();
    }

    private String renderTable(Element table) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Element tr : table.getElementsByTag("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.children()) {
                if (cell.tagName().equalsIgnoreCase("th") || cell.tagName().equalsIgnoreCase("td")) {
                    cells.add(inline(cell).strip().replace("\n", " "));
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (first) {
                sb.append("|").append(" --- |".repeat(cells.size())).append("\n");
                first = false;
            }
        }
        return sb.toString();
    }

    private String inline(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node child : e.childNodes()) {
            if (child instanceof TextNode tn) {
                sb.append(tn.text());
            } else if (child instanceof Element ce) {
                String tag = ce.tagName().toLowerCase();
                switch (tag) {
                    case "strong", "b" -> sb.append("**").append(inline(ce).strip()).append("**");
                    case "em", "i" -> sb.append("*").append(inline(ce).strip()).append("*");
                    case "code" -> sb.append("`").append(inline(ce).strip()).append("`");
                    case "a" -> sb.append("[").append(inline(ce).strip()).append("](").append(ce.attr("href")).append(")");
                    case "br" -> sb.append("  \n");
                    case "ac:structured-macro" -> sb.append(renderMacro(ce).strip());
                    default -> sb.append(inline(ce));
                }
            }
        }
        return sb.toString();
    }
}
