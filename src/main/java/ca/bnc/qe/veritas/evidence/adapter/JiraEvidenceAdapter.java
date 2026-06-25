package ca.bnc.qe.veritas.evidence.adapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.Hints;
import ca.bnc.qe.veritas.evidence.Redactor;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import ca.bnc.qe.veritas.ingest.AdfToMarkdown;
import ca.bnc.qe.veritas.ingest.NormalizedDoc;
import ca.bnc.qe.veritas.ingest.TestBasisExtractor;
import ca.bnc.qe.veritas.ingest.TestBasisItem;
import ca.bnc.qe.veritas.ingest.TestBasisKind;
import ca.bnc.qe.veritas.integration.jira.JiraClient;
import ca.bnc.qe.veritas.integration.jira.JiraIssue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns Jira issues into intent evidence: the summary becomes an issue-level {@code REQUIREMENT} unit and the
 * description yields acceptance-criteria / business-rule units (via the shared {@link TestBasisExtractor}).
 * Each issue is processed in its own try/catch so one malformed issue doesn't abort the whole fetch (design §1.3).
 *
 * <p>Each unit carries the issue's <b>lifecycle</b> ({@code TO_DO|IN_PROGRESS|DONE|DESCOPED}), priority, and
 * related-issue links (§1.2 — the two-axis status engine reads the lifecycle), and its labels + components feed
 * the clustering hints. The description is taken as Cloud <b>ADF</b> (an object → {@link AdfToMarkdown}) or
 * Server/DC <b>wiki markup</b> (a plain string → used as-is), so Server/DC bodies are no longer silently empty.
 */
@Component
@Slf4j
public class JiraEvidenceAdapter {

    /** Fields fetched for evidence: text + the widened metadata that drives status, hints, and traceability. */
    private static final List<String> FIELDS =
            List.of("summary", "description", "status", "resolution", "priority", "labels", "components", "issuelinks");

    private final JiraClient jira;
    private final AdfToMarkdown adf;
    private final TestBasisExtractor extractor;

    public JiraEvidenceAdapter(JiraClient jira, AdfToMarkdown adf, TestBasisExtractor extractor) {
        this.jira = jira;
        this.adf = adf;
        this.extractor = extractor;
    }

    public SourceExtraction extract(String jql, int maxResults) {
        List<JiraIssue> issues;
        try {
            issues = jira.search(jql, FIELDS, maxResults);
        } catch (Exception e) {
            log.warn("Jira search failed for jql [{}]: {}", jql, e.getMessage());
            return new SourceExtraction(SourceKind.JIRA, List.of(), 1, 0,
                    List.of("Jira search failed: " + e.getMessage()), 0);
        }

        List<EvidenceUnit> units = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int redactions = 0;
        int fetched = 0;
        int descoped = 0;
        for (JiraIssue issue : issues) {
            if ("DESCOPED".equals(issue.lifecycle())) {
                // Won't-do/rejected → out of scope (§1.2): not a usable fetch, so an all-descoped release reports
                // fetched=0 and trips the §1.3 hard-fail gate rather than silently yielding an empty strategy.
                descoped++;
                continue;
            }
            try {
                // Build the issue's units AND its redaction subtotal locally, committing both only on full success —
                // so a failed issue contributes neither phantom units nor a phantom redaction count (atomic).
                List<EvidenceUnit> issueUnits = new ArrayList<>();
                int issueRedactions = 0;
                String lifecycle = issue.lifecycle();
                String priority = issue.priority();
                Set<String> links = Set.copyOf(issue.links());

                Redactor.Result summary = Redactor.redact(issue.summary());
                issueRedactions += summary.count();
                issueUnits.add(EvidenceUnit.jira(EvidenceId.issue(issue.key()), UnitType.REQUIREMENT,
                        issue.key(), summary.text(), null, lifecycle, priority, links, hintsFor(summary.text(), issue)));

                String md = descriptionMarkdown(issue.description());
                NormalizedDoc doc = new NormalizedDoc("jira", issue.key(), issue.summary(), md);
                for (TestBasisItem item : extractor.extract(doc)) {
                    Redactor.Result r = Redactor.redact(item.text());
                    issueRedactions += r.count();
                    // Id is hashed from the raw source text (stable as redaction patterns evolve); hints are derived
                    // from the REDACTED text so a scrubbed token can't leak into the clustering signal / preview UI.
                    // Every unit of an issue carries the issue's lifecycle/priority/links so the status engine sees it
                    // even when only a sub-part unit lands in a feature cluster.
                    issueUnits.add(EvidenceUnit.jira(EvidenceId.jiraPart(issue.key(), item.text()),
                            mapKind(item.kind()), issue.key() + " — " + item.kind(), r.text(), null,
                            lifecycle, priority, links, hintsFor(r.text(), issue)));
                }
                units.addAll(issueUnits);
                redactions += issueRedactions;
                fetched++;
            } catch (Exception e) {
                failed.add(issue.key() + ": " + e.getMessage());
                log.warn("Skipping Jira issue {}: {}", issue.key(), e.getMessage());
            }
        }
        if (descoped > 0) {
            log.info("Excluded {} descoped (won't-do/rejected) Jira issue(s) from scope for jql [{}]", descoped, jql);
        }
        // A selected source that returns nothing usable is a §1.3 hard-fail signal (requested>0, fetched==0) — note
        // descoped issues count toward 'requested' but not 'fetched', so an all-descoped release trips the gate.
        int requested = Math.max(1, issues.size());
        return new SourceExtraction(SourceKind.JIRA, units, requested, fetched, failed, redactions);
    }

    /** Description as markdown: a Cloud ADF object via {@link AdfToMarkdown}, or a Server/DC wiki-markup string as-is. */
    private String descriptionMarkdown(com.fasterxml.jackson.databind.JsonNode description) {
        if (description == null || description.isNull()) {
            return "";
        }
        return description.isTextual() ? description.asText() : adf.toMarkdown(description);
    }

    /**
     * Clustering hints: the unit's text tokens plus the issue's <b>components</b>. Components are sparse, structural
     * domain groupings (e.g. "Policies", "Authentication") — a high-signal grouping that, with the seed's ≥2-shared
     * rule, boosts genuinely-related issues without merging unrelated ones. <b>Labels are deliberately NOT used as a
     * hint</b>: they're free-form and often generic ("backend", "tech-debt"), and feeding a tag shared across many
     * issues to the conservative seed risks <b>over-merge</b> — which, unlike under-merge, the tagger/UI can't undo.
     * (Labels are still parsed onto {@link JiraIssue} for other uses.)
     */
    private static Set<String> hintsFor(String text, JiraIssue issue) {
        Set<String> hints = new LinkedHashSet<>(Hints.fromText(text));
        issue.components().forEach(c -> hints.add(c.toLowerCase(Locale.ROOT)));
        return hints;
    }

    private static UnitType mapKind(TestBasisKind k) {
        return switch (k) {
            case REQUIREMENT -> UnitType.REQUIREMENT;
            case ACCEPTANCE_CRITERIA -> UnitType.ACCEPTANCE_CRITERIA;
            case BUSINESS_RULE, CONSTRAINT, ENUM -> UnitType.BUSINESS_RULE;
            case EXAMPLE -> UnitType.DESIGN;
        };
    }
}
