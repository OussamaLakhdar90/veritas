package ca.bnc.qe.veritas.evidence.adapter;

import java.util.ArrayList;
import java.util.List;
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
 * <p>Lifecycle/priority/labels/components are <b>not</b> populated yet — that's the Jira field-widening follow-up
 * (it touches {@code JiraIssue} and both edition clients). Until then hints come from the summary text.
 *
 * <p><b>Edition caveat:</b> {@code AdfToMarkdown} assumes Jira <b>Cloud</b> ADF. On Server/DC the description is
 * <b>wiki markup</b>, so only the summary contributes until the widening adds a wiki→markdown path (tracked in the
 * §8 widening checklist) — otherwise the Server/DC body is silently empty.
 */
@Component
@Slf4j
public class JiraEvidenceAdapter {

    /** Same lean field set IngestService uses; widened to labels/components/status by the follow-up. */
    private static final List<String> FIELDS = List.of("summary", "description");

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
        for (JiraIssue issue : issues) {
            try {
                // Build the issue's units AND its redaction subtotal locally, committing both only on full success —
                // so a failed issue contributes neither phantom units nor a phantom redaction count (atomic).
                List<EvidenceUnit> issueUnits = new ArrayList<>();
                int issueRedactions = 0;

                Redactor.Result summary = Redactor.redact(issue.summary());
                issueRedactions += summary.count();
                issueUnits.add(EvidenceUnit.of(EvidenceId.issue(issue.key()), SourceKind.JIRA, UnitType.REQUIREMENT,
                        issue.key(), summary.text(), null, Hints.fromText(summary.text())));

                String md = issue.description() == null ? "" : adf.toMarkdown(issue.description());
                NormalizedDoc doc = new NormalizedDoc("jira", issue.key(), issue.summary(), md);
                for (TestBasisItem item : extractor.extract(doc)) {
                    Redactor.Result r = Redactor.redact(item.text());
                    issueRedactions += r.count();
                    // Id is hashed from the raw source text (stable as redaction patterns evolve); hints are derived
                    // from the REDACTED text so a scrubbed token can't leak into the clustering signal / preview UI.
                    issueUnits.add(EvidenceUnit.of(EvidenceId.jiraPart(issue.key(), item.text()), SourceKind.JIRA,
                            mapKind(item.kind()), issue.key() + " — " + item.kind(), r.text(), null,
                            Hints.fromText(r.text())));
                }
                units.addAll(issueUnits);
                redactions += issueRedactions;
                fetched++;
            } catch (Exception e) {
                failed.add(issue.key() + ": " + e.getMessage());
                log.warn("Skipping Jira issue {}: {}", issue.key(), e.getMessage());
            }
        }
        // A selected source that returns nothing is a §1.3 hard-fail signal (requested>0, fetched==0).
        int requested = Math.max(1, issues.size());
        return new SourceExtraction(SourceKind.JIRA, units, requested, fetched, failed, redactions);
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
