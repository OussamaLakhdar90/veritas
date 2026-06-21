package ca.bnc.qe.veritas.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Extracts a compact, citable test basis from a {@link NormalizedDoc}'s markdown — keeps requirements /
 * acceptance criteria / business rules / table rows, drops narrative boilerplate. Each item carries a
 * traceability id ({@code <sourceId>#<section>-<n>}) so LLM output can cite it (no orphans, CTFL §1.4.4).
 */
@Component
public class TestBasisExtractor {

    public List<TestBasisItem> extract(NormalizedDoc doc) {
        List<TestBasisItem> items = new ArrayList<>();
        if (doc == null || doc.markdown() == null) {
            return items;
        }
        String section = "general";
        boolean acSection = false;
        int counter = 0;

        for (String raw : doc.markdown().split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                String heading = line.replaceFirst("^#+\\s*", "");
                section = slug(heading);
                acSection = heading.toLowerCase(Locale.ROOT).contains("acceptance");
                continue;
            }
            if (line.startsWith("|")) {
                if (line.matches("\\|[\\s|:\\-]+\\|?")) {
                    continue; // table separator row
                }
                String cells = line.replaceAll("^\\|", "").replaceAll("\\|$", "").trim()
                        .replaceAll("\\s*\\|\\s*", " — ");
                items.add(item(doc, section, ++counter, TestBasisKind.BUSINESS_RULE, cells));
                continue;
            }
            if (line.startsWith("- ") || line.matches("\\d+\\.\\s.*")) {
                String text = line.replaceFirst("^(-\\s|\\d+\\.\\s)", "").trim();
                TestBasisKind kind = acSection ? TestBasisKind.ACCEPTANCE_CRITERIA
                        : (containsModal(text) ? TestBasisKind.BUSINESS_RULE : TestBasisKind.REQUIREMENT);
                items.add(item(doc, section, ++counter, kind, text));
                continue;
            }
            if (line.matches("(?i)^(given|when|then|and|but)\\b.*")) {
                items.add(item(doc, section, ++counter, TestBasisKind.ACCEPTANCE_CRITERIA, line));
                continue;
            }
            if (containsModal(line)) {
                items.add(item(doc, section, ++counter, TestBasisKind.BUSINESS_RULE, line));
            }
        }
        return items;
    }

    private boolean containsModal(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        return t.contains(" must ") || t.contains(" shall ") || t.contains(" should ")
                || t.startsWith(" must ") || t.startsWith(" shall ");
    }

    private TestBasisItem item(NormalizedDoc doc, String section, int n, TestBasisKind kind, String text) {
        return new TestBasisItem(doc.sourceId() + "#" + section + "-" + n, doc.sourceId(), kind, text);
    }

    private String slug(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "sec" : (slug.length() > 24 ? slug.substring(0, 24) : slug);
    }
}
