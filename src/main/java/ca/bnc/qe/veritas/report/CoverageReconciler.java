package ca.bnc.qe.veritas.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.finding.Finding;

/**
 * Keeps the report's coverage verdict (§7) honest: an LLM finding must not claim a source/handler/DTO/security
 * input was "not supplied" while §7 reports full coverage. When the extractor achieved full coverage (no blind
 * spots), every such disclaimer is provably false — exactly the gap class the extractor records as a blind spot —
 * so we strip those clauses; the surviving disclaimers (under partial coverage) are real and downgrade §7.
 */
public final class CoverageReconciler {

    private CoverageReconciler() {
    }

    /** A clause claiming a source/handler/DTO/security input was not supplied/provided/available. */
    private static final Pattern MISSING_SOURCE = Pattern.compile(
            "(?i)(source|file|class|dto|handler|advice|controller|security\\s+config\\w*)[^.;]{0,60}"
                    + "(not\\s+(supplied|provided|available)|not in the scanned sources|unavailable|"
                    + "was not (supplied|provided))");

    public static boolean looksLikeMissingSource(String text) {
        return text != null && MISSING_SOURCE.matcher(text).find();
    }

    public static boolean anyMissingSourceDisclaimer(Collection<Finding> findings) {
        if (findings == null) {
            return false;
        }
        for (Finding f : findings) {
            if (looksLikeMissingSource(f.getSummary()) || looksLikeMissingSource(f.getExplanation())) {
                return true;
            }
        }
        return false;
    }

    /**
     * When coverage is full (no extractor blind spots), strip false "source not supplied" clauses from LLM findings
     * so the report can't contradict its own §7 verdict. When coverage is partial, disclaimers may be real and are
     * left untouched.
     */
    public static List<Finding> stripFalseSourceDisclaimers(List<Finding> findings, ApiModel code) {
        boolean fullCoverage = code != null && (code.blindSpots() == null || code.blindSpots().isEmpty());
        if (!fullCoverage || findings == null) {
            return findings;
        }
        List<Finding> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            boolean llm = f.getOrigin() != null && !f.getOrigin().equalsIgnoreCase("DETERMINISTIC");
            boolean disclaims = looksLikeMissingSource(f.getSummary()) || looksLikeMissingSource(f.getExplanation());
            if (!llm || !disclaims) {
                out.add(f);
                continue;
            }
            out.add(f.toBuilder()
                    .summary(removeMissingSourceClauses(f.getSummary()))
                    .explanation(removeMissingSourceClauses(f.getExplanation()))
                    .build());
        }
        return out;
    }

    private static String removeMissingSourceClauses(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String sentence : text.split("(?<=[.;])\\s+")) {
            if (looksLikeMissingSource(sentence)) {
                continue;   // drop the false disclaimer sentence
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(sentence);
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? "Veritas parsed all referenced sources for this scan." : cleaned;
    }
}
