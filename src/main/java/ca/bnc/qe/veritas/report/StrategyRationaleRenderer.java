package ca.bnc.qe.veritas.report;

import ca.bnc.qe.veritas.persistence.TestStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Renders the <b>Test Strategy Rationale</b> — the "why" companion to a strategy. For each strategic decision it
 * gives a full explanatory section: the ISTQB <b>Principle</b> (deterministic, from {@link IstqbGlossary} — never
 * invented), <b>Why here</b> (derived from the strategy's own structured fields), and <b>How it serves the
 * objective</b> (the risk/objective the decision addresses). It is a projection of the same strategy
 * deliverable, so it cannot drift from it. Cites by named concept; flags anything without a syllabus basis.
 */
@Component
public class StrategyRationaleRenderer {

    private final ObjectMapper mapper;

    public StrategyRationaleRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String renderHtml(TestStrategy strategy) {
        JsonNode d;
        try {
            d = strategy.getDeliverableJson() == null ? mapper.createObjectNode()
                    : mapper.readTree(strategy.getDeliverableJson());
        } catch (Exception e) {
            d = mapper.createObjectNode();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<title>Test Strategy Rationale — ").append(esc(nz(strategy.getServiceName())))
                .append("</title><style>").append(css()).append("</style></head><body>");
        sb.append("<div class=\"hdr\"><div class=\"hd\">Test Strategy Rationale</div><div class=\"sub\">")
                .append(esc(nz(strategy.getServiceName())))
                .append(strategy.getVersion() != null ? " · v" + strategy.getVersion() : "")
                .append("</div></div><div class=\"content\">");
        sb.append("<p class=\"intro\">Why this strategy is shaped the way it is — each decision explained against its "
                + "ISTQB principle and the risk or objective it serves.</p>");

        // Scope
        JsonNode scope = d.path("scope");
        if (scope.isObject() && scope.has("objectives")) {
            sb.append(section("Scope &amp; objectives", "Test Scope",
                    "The strategy fixes what is and isn't tested up front so coverage expectations are unambiguous.",
                    "Objectives: " + joinArray(scope.path("objectives")) + ".", null));
        }

        // Risk-based prioritization
        JsonNode risks = d.path("riskRegister");
        if (risks.isArray() && risks.size() > 0) {
            StringBuilder high = new StringBuilder();
            int highCount = 0;
            for (JsonNode r : risks) {
                String level = r.path("level").asText("");
                if (level.equalsIgnoreCase("HIGH") || level.equalsIgnoreCase("VERY HIGH") || level.equalsIgnoreCase("VH")) {
                    highCount++;
                    high.append("<li><strong>").append(esc(r.path("id").asText(""))).append("</strong> — ")
                            .append(esc(r.path("description").asText(""))).append(" (mitigation: ")
                            .append(esc(r.path("mitigation").asText("—"))).append(")</li>");
                }
            }
            String why = "The risk register identifies " + risks.size() + " risk(s); the " + highCount
                    + " HIGH/VERY-HIGH risk(s) drive where coverage is deepest and earliest.";
            String serves = highCount == 0 ? "No HIGH risks — coverage is spread evenly."
                    : "Coverage is concentrated on:<ul>" + high + "</ul>";
            sb.append(section("Risk-based prioritization", "CTAL-TM — Risk-Based Testing", null, why, serves));
        }

        // Techniques
        JsonNode techniques = d.path("testApproach").path("techniques");
        if (techniques.isArray() && techniques.size() > 0) {
            for (JsonNode t : techniques) {
                String name = t.path("name").asText("");
                String cite = t.path("citation").asText(name);
                String why = !t.path("rationale").asText("").isBlank() ? t.path("rationale").asText() : null;
                String serves = t.hasNonNull("riskId") ? "Addresses risk " + esc(t.path("riskId").asText()) + "." : null;
                sb.append(section("Technique — " + esc(name), cite.isBlank() ? name : cite, null, why, serves));
            }
        }

        // Levels & types
        JsonNode approach = d.path("testApproach");
        if (approach.has("levels") || approach.has("types")) {
            String why = "Levels: " + joinArray(approach.path("levels")) + ". Types: " + joinArray(approach.path("types")) + ".";
            sb.append(section("Test levels &amp; types", "Test Levels", null, why,
                    "Each level catches defects at the cheapest appropriate stage; each type assesses a distinct quality dimension."));
        }

        // Exit criteria
        JsonNode exit = d.path("exitCriteria");
        if (exit.isArray() && exit.size() > 0) {
            StringBuilder items = new StringBuilder("<ul>");
            for (JsonNode c : exit) {
                items.append("<li>").append(esc(c.path("criterion").asText("")))
                        .append(c.hasNonNull("metric") ? " <em>(" + esc(c.path("metric").asText()) + ")</em>" : "")
                        .append("</li>");
            }
            items.append("</ul>");
            sb.append(section("Exit criteria", "CTAL-TM — Exit Criteria", null,
                    "Release readiness is tied to measurable conditions, not a subjective judgement.", items.toString()));
        }

        sb.append("<p class=\"foot\">Generated by Veritas. ISTQB principles are quoted by named concept; any decision "
                + "without a syllabus basis is marked “team convention — beyond syllabus”. Bound to the "
                + "strategy deliverable (cannot drift).</p>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    /** One explanatory block: Principle (glossary, deterministic) + Why here + How it serves. */
    private String section(String title, String concept, String fallbackPrinciple, String why, String serves) {
        String principle = IstqbGlossary.explain(concept);
        if (principle == null) {
            principle = fallbackPrinciple != null ? fallbackPrinciple : "Team convention — beyond syllabus.";
        }
        StringBuilder sb = new StringBuilder("<section class=\"r\"><h2>").append(title).append("</h2>");
        sb.append("<p><strong>Principle.</strong> ").append(esc(principle)).append("</p>");
        if (why != null && !why.isBlank()) {
            sb.append("<p><strong>Why here.</strong> ").append(why).append("</p>");
        }
        if (serves != null && !serves.isBlank()) {
            sb.append("<p><strong>How it serves the objective.</strong> ").append(serves).append("</p>");
        }
        return sb.append("</section>").toString();
    }

    private String joinArray(JsonNode arr) {
        if (!arr.isArray() || arr.size() == 0) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(esc(arr.get(i).asText()));
        }
        return sb.toString();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String css() {
        return "body{font-family:Inter,Segoe UI,system-ui,sans-serif;color:#1a1a2e;margin:0;background:#f5f6fa;line-height:1.6}"
                + ".hdr{background:linear-gradient(135deg,#1a1a2e,#0f3460);color:#fff;padding:2rem}"
                + ".hd{font-size:1.5rem;font-weight:600}.sub{opacity:.85}"
                + ".content{max-width:900px;margin:0 auto;padding:1.5rem 2rem}"
                + ".intro{color:#475069}"
                + "section.r{background:#fff;border:1px solid #e3e6eb;border-left:4px solid #0f3460;border-radius:0 10px 10px 0;"
                + "padding:1rem 1.25rem;margin-top:1rem}"
                + "section.r h2{font-size:1.05rem;margin:0 0 .5rem}"
                + "section.r p{margin:.4rem 0;font-size:.92rem}"
                + "ul{margin:.3rem 0 .3rem 1.1rem}"
                + ".foot{color:#9aa1ae;font-size:.78rem;margin-top:2rem;border-top:1px solid #e3e6eb;padding-top:.8rem}";
    }
}
