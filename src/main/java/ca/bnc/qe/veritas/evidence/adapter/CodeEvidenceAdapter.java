package ca.bnc.qe.veritas.evidence.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.engine.model.SourceRef;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.evidence.EvidenceUnit;
import ca.bnc.qe.veritas.evidence.Hints;
import ca.bnc.qe.veritas.evidence.Redactor;
import ca.bnc.qe.veritas.evidence.SourceKind;
import ca.bnc.qe.veritas.evidence.UnitType;
import org.springframework.stereotype.Component;

/**
 * Turns the deterministic code model ({@link ApiModel}, from {@code JavaSpringExtractor}) into evidence:
 * one {@code ENDPOINT} unit per route, one {@code DTO_CONSTRAINT} unit per shaped schema, and one
 * {@code GLOBAL_CAVEAT} unit per static-analysis blind spot (the cross-cutting signal the strategy basis
 * previously dropped). Pure: takes an already-built model, so it's trivially testable with a fixture (design §2).
 *
 * <p>The {@code CODE:<Class>#<HTTP> <path>} id derives the class from the endpoint's source file name; the
 * method+path is the routing key, so the id is unique by construction (a same-method-same-path collision would be
 * an actual Spring mapping conflict). A first-class {@code Endpoint.controllerClass} is a possible follow-up if
 * multi-controller files ever appear.
 */
@Component
public class CodeEvidenceAdapter {

    /** Build evidence from an already-extracted code model. {@code requested = fetched = 1} when a model is present. */
    public SourceExtraction extract(ApiModel model) {
        if (model == null) {
            return new SourceExtraction(SourceKind.CODE, List.of(), 0, 0, List.of(), 0);
        }
        List<EvidenceUnit> units = new ArrayList<>();
        int[] redactions = {0};

        for (Endpoint e : model.endpoints()) {
            String cls = classFromSource(e.source());
            String id = EvidenceId.endpoint(cls, e.method().name(), e.pathTemplate());
            units.add(unit(id, UnitType.ENDPOINT, e.signature(), endpointText(e), refLink(e.source()),
                    Hints.fromPath(e.pathTemplate()), redactions));
        }

        if (model.schemas() != null) {
            for (SchemaModel s : model.schemas().values()) {
                if (isShaped(s)) {
                    String id = EvidenceId.dtoConstraint(s.name(), "schema");
                    units.add(unit(id, UnitType.DTO_CONSTRAINT, "Schema " + s.name(), schemaText(s),
                            refLink(s.source()), Hints.fromText(s.name()), redactions));
                }
            }
        }

        for (String caveat : model.blindSpots()) {
            if (caveat != null && !caveat.isBlank()) {
                String id = EvidenceId.caveat(caveat);
                units.add(unit(id, UnitType.GLOBAL_CAVEAT, "Analysis caveat", caveat, null,
                        java.util.Set.of("cross-cutting"), redactions));
            }
        }

        // §1.3 consistency: a present-but-empty model (e.g. a repo with zero Spring controllers) produced no usable
        // units → fetched=0 so it's dropped from the mix and trips the hard-fail, matching Jira/Confluence.
        int fetched = units.isEmpty() ? 0 : 1;
        return new SourceExtraction(SourceKind.CODE, units, 1, fetched, List.of(), redactions[0]);
    }

    private static EvidenceUnit unit(String id, UnitType type, String title, String text, String link,
                                     java.util.Set<String> hints, int[] redactions) {
        Redactor.Result r = Redactor.redact(text);
        redactions[0] += r.count();
        return EvidenceUnit.of(id, SourceKind.CODE, type, title, r.text(), link, hints);
    }

    private static String endpointText(Endpoint e) {
        StringBuilder t = new StringBuilder(e.signature());
        if (!e.params().isEmpty()) {
            t.append(" — parameters: ").append(e.params().stream()
                    .map(p -> p.name() + " (" + p.location() + (p.required() ? ", required" : "") + ")")
                    .collect(Collectors.joining(", ")));
        }
        if (!e.responses().isEmpty()) {
            t.append("; responses: ").append(e.responses().stream()
                    .map(r -> r.statusCode() + (r.schemaRef() != null ? " " + r.schemaRef() : ""))
                    .collect(Collectors.joining(", ")));
        }
        if (!e.security().isEmpty()) {
            t.append("; secured: ").append(String.join(", ", e.security()));
        }
        return t.toString();
    }

    private static boolean isShaped(SchemaModel s) {
        return s != null && ((s.fields() != null && !s.fields().isEmpty())
                || (s.enumValues() != null && !s.enumValues().isEmpty()));
    }

    private static String schemaText(SchemaModel s) {
        StringBuilder t = new StringBuilder("Schema ").append(s.name());
        if (s.fields() != null && !s.fields().isEmpty()) {
            t.append(": ").append(s.fields().stream()
                    .map(f -> f.jsonName() + " (" + f.type() + (f.required() ? ", required" : "") + ")")
                    .collect(Collectors.joining(", ")));
        }
        if (s.enumValues() != null && !s.enumValues().isEmpty()) {
            t.append("; allowed values: ").append(String.join(", ", s.enumValues()));
        }
        return t.toString();
    }

    /** A plain file:line reference (the source path is already repo-relative); the clickable URL is built later. */
    private static String refLink(SourceRef src) {
        if (src == null || src.location() == null) {
            return null;
        }
        return src.location() + (src.startLine() != null ? ":" + src.startLine() : "");
    }

    static String classFromSource(SourceRef src) {
        if (src == null || src.location() == null) {
            return "Code";
        }
        String base = src.location().replace('\\', '/');
        base = base.contains("/") ? base.substring(base.lastIndexOf('/') + 1) : base;
        return base.endsWith(".java") ? base.substring(0, base.length() - 5) : base;
    }
}
