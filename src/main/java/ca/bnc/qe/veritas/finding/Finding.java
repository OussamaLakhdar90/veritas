package ca.bnc.qe.veritas.finding;

import java.util.List;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import lombok.Builder;
import lombok.Value;

/**
 * One contract-validation finding. Deterministic findings are produced by the diff engine; the LLM later
 * enriches {@code explanation}/{@code proposedFix}/{@code citation} and adds L5/L6 findings.
 */
@Value
@Builder(toBuilder = true)
public class Finding {
    String findingId;            // stable hash of type + endpoint + locus
    FindingType type;
    Layer layer;
    Severity severity;
    Confidence confidence;
    String origin;               // DETERMINISTIC | LLM
    String service;
    String endpoint;             // "METHOD /path" or null for global/L1
    @Builder.Default
    List<String> affectedEndpoints = List.of();  // >1 when several endpoints share this same root cause (e.g. a shared DTO field)
    String specLocus;            // "<specSchemaName>#<fieldJsonName>" — the spec-side root cause of a schema-field finding,
                                 // used to collapse the SAME shared spec-schema field flagged across endpoints (# separates
                                 // because a spec schema name can contain dots). Null for non-schema-field findings.
    String specSource;           // which spec this is about (or "code-vs-repo-spec", "repo-spec-vs-confluence-spec")
    String summary;              // deterministic one-liner
    String explanation;          // LLM (nullable)
    SourceRef codeEvidence;      // file + lines + snippet (nullable)
    String currentYamlFragment;  // nullable
    String proposedFix;          // nullable until enriched
    String citation;             // ISTQB/CTFL reference (nullable)
    @Builder.Default
    String status = "OPEN";       // disposition: OPEN | ACCEPTED | REJECTED | TRIAGED | WONT_FIX | …
    String reviewedBy;           // who set the disposition (nullable; populated on a live re-render from persistence)
    java.time.Instant reviewedAt; // when the disposition was set (nullable)
    @Builder.Default
    boolean aiDisputed = false;   // the reconcile LLM flagged this DETERMINISTIC finding as a likely false positive…
    String aiDisputeReason;       // …with this code-grounded reason. Never deletes/downgrades severity — only moves the
                                  // finding out of the automatic release-blocking gate and into the review view.
    Severity userSeverity;        // a human's severity override (nullable) — wins over the engine severity at the gate.
                                  // The engine `severity` is the deterministic "suggested" default the UI shows.

    /** The severity the gate and report must use: a human override wins over the engine's classification. */
    public Severity effectiveSeverity() {
        return userSeverity != null ? userSeverity : severity;
    }
}
