package ca.bnc.qe.veritas.testmgmt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ca.bnc.qe.veritas.engine.extract.java.JavaSpringExtractor;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.ConstraintSet;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import ca.bnc.qe.veritas.engine.model.FieldModel;
import ca.bnc.qe.veritas.engine.model.ParamModel;
import ca.bnc.qe.veritas.engine.model.ResponseModel;
import ca.bnc.qe.veritas.engine.model.SchemaModel;
import ca.bnc.qe.veritas.evidence.EvidenceId;
import ca.bnc.qe.veritas.ingest.IngestService;
import ca.bnc.qe.veritas.ingest.TestBasis;
import ca.bnc.qe.veritas.ingest.TestBasisItem;
import org.springframework.stereotype.Component;

/** Builds the test basis text fed to LLM test-management skills — from the codebase or from Jira/Confluence. */
@Component
public class BasisBuilder {

    private final JavaSpringExtractor javaSpringExtractor;
    private final IngestService ingestService;

    public BasisBuilder(JavaSpringExtractor javaSpringExtractor, IngestService ingestService) {
        this.javaSpringExtractor = javaSpringExtractor;
        this.ingestService = ingestService;
    }

    /**
     * A RICH code basis: not just the endpoint list, but each endpoint's params (+validation constraints), request
     * body (+whether it's {@code @Valid}-ated), responses, and security; plus a "Data models" section with each DTO's
     * fields and constraints. The strategy LLM can then reason about field-level and security risks — not just the
     * path/verb shape, which is all the old signature-only basis exposed (the gap the dogfood surfaced).
     */
    public String fromRepo(Path repoPath) {
        ApiModel model = javaSpringExtractor.extract(repoPath);
        StringBuilder sb = new StringBuilder("API surface (from code):\n");
        for (Endpoint e : model.endpoints()) {
            // Mint a stable, non-guessable content-hash id per basis item so downstream conditions/cases can cite a
            // real basis id (closed-world), not free text — the model can only copy an id it was shown.
            sb.append("- [").append(basisId("CODE", e.signature())).append("] ").append(e.signature());
            if (e.security() != null && !e.security().isEmpty()) {
                sb.append("  [secured: ").append(String.join(", ", e.security())).append("]");
            }
            sb.append("\n");
            if (e.params() != null && !e.params().isEmpty()) {
                sb.append("    params: ").append(renderParams(e.params())).append("\n");
            }
            if (e.requestBody() != null && e.requestBody().schemaRef() != null && !e.requestBody().schemaRef().isBlank()) {
                sb.append("    body: ").append(e.requestBody().schemaRef())
                        .append(e.requestBody().validated() ? " (validated)" : " (NOT @Valid)").append("\n");
            }
            if (e.responses() != null && !e.responses().isEmpty()) {
                sb.append("    responses: ").append(renderResponses(e.responses())).append("\n");
            }
        }
        if (model.schemas() != null && !model.schemas().isEmpty()) {
            sb.append("\nData models (from code):\n");
            model.schemas().forEach((name, s) ->
                    sb.append("- [").append(basisId("DTO", name)).append("] ").append(renderSchema(s)).append("\n"));
        }
        return sb.toString();
    }

    /** A stable, non-guessable basis-item id: {@code CODE-<hash8>} / {@code DTO-<hash8>} over the item's own text. */
    private static String basisId(String prefix, String text) {
        return prefix + "-" + EvidenceId.hash8(text == null ? "" : text);
    }

    private String renderParams(List<ParamModel> params) {
        List<String> parts = new ArrayList<>();
        for (ParamModel p : params) {
            StringBuilder b = new StringBuilder(p.name()).append(" (")
                    .append(p.location() == null ? "?" : p.location().name().toLowerCase());
            if (p.type() != null) {
                b.append(", ").append(p.type());
            }
            if (p.required()) {
                b.append(", required");
            }
            b.append(")");
            String c = renderConstraints(p.constraints());
            if (!c.isEmpty()) {
                b.append(" ").append(c);
            }
            parts.add(b.toString());
        }
        return String.join("; ", parts);
    }

    private String renderResponses(List<ResponseModel> responses) {
        List<String> parts = new ArrayList<>();
        for (ResponseModel r : responses) {
            parts.add(r.statusCode() + (r.schemaRef() != null && !r.schemaRef().isBlank() ? " " + r.schemaRef() : ""));
        }
        return String.join(", ", parts);
    }

    private String renderSchema(SchemaModel s) {
        if (s.enumValues() != null && !s.enumValues().isEmpty()) {
            return s.name() + " = enum [" + String.join(", ", s.enumValues()) + "]";
        }
        if (s.fields() == null || s.fields().isEmpty()) {
            return s.name();
        }
        List<String> parts = new ArrayList<>();
        for (FieldModel f : s.fields()) {
            StringBuilder b = new StringBuilder(f.jsonName()).append(": ").append(f.type() == null ? "?" : f.type());
            if (f.required()) {
                b.append(" required");
            }
            String c = renderConstraints(f.constraints());
            if (!c.isEmpty()) {
                b.append(" ").append(c);
            }
            if (f.refSchema() != null && !f.refSchema().isBlank()) {
                b.append(" →").append(f.refSchema());
            }
            parts.add(b.toString());
        }
        return s.name() + ": " + String.join("; ", parts);
    }

    /** Compact validation-constraint rendering, e.g. {@code {minLen 1, maxLen 30, enum [A,B]}}; "" when none. */
    private String renderConstraints(ConstraintSet c) {
        if (c == null || c.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (c.minLength() != null) {
            parts.add("minLen " + c.minLength());
        }
        if (c.maxLength() != null) {
            parts.add("maxLen " + c.maxLength());
        }
        if (c.minimum() != null) {
            parts.add("min " + c.minimum());
        }
        if (c.maximum() != null) {
            parts.add("max " + c.maximum());
        }
        if (c.pattern() != null) {
            parts.add("pattern " + c.pattern());
        }
        if (c.enumValues() != null && !c.enumValues().isEmpty()) {
            parts.add("enum [" + String.join(",", c.enumValues()) + "]");
        }
        if (c.format() != null) {
            parts.add("format " + c.format());
        }
        return "{" + String.join(", ", parts) + "}";
    }

    public String fromIngest(String jql, List<String> pageIds) {
        TestBasis basis = ingestService.assemble(jql, pageIds, 100);
        StringBuilder sb = new StringBuilder("Test basis (from Jira/Confluence):\n");
        for (TestBasisItem item : basis.items()) {
            sb.append("- [").append(item.id()).append("] ").append(item.kind()).append(": ").append(item.text()).append("\n");
        }
        return sb.toString();
    }
}
