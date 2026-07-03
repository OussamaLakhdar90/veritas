package ca.bnc.qe.veritas.engine.extract.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

/**
 * Stateless helpers for reading JavaParser annotations by name/member — the shared seam every extraction cluster
 * (status, security, constraints, routing, type-mapping) depends on. Lifted out of JavaSpringExtractor so the god class
 * can be split into collaborators that all static-import these; call sites elsewhere are unchanged.
 */
final class AnnotationSupport {

    private AnnotationSupport() {
    }

    static boolean has(NodeWithAnnotations<?> n, String name) {
        return n.getAnnotationByName(name).isPresent();
    }

    /**
     * True if the node carries {@code name} directly, OR carries a custom annotation whose declaration (in the
     * scanned sources) is meta-annotated with {@code name} — e.g. a composed @RequestParam-based param annotation.
     * Only fires for genuine composed annotations (the same way Spring resolves them), so no false positives for
     * resolver-backed annotations that don't compose a binding.
     */
    static boolean hasMeta(NodeWithAnnotations<?> n, String name, Map<String, TypeDeclaration<?>> types) {
        if (has(n, name)) {
            return true;
        }
        for (AnnotationExpr a : n.getAnnotations()) {
            if (types.get(a.getNameAsString()) instanceof AnnotationDeclaration decl && has(decl, name)) {
                return true;
            }
        }
        return false;
    }

    static Optional<AnnotationExpr> getAnnotation(NodeWithAnnotations<?> n, String name) {
        return n.getAnnotationByName(name);
    }

    static String firstString(AnnotationExpr a, String... members) {
        if (a instanceof SingleMemberAnnotationExpr sm) {
            return literal(sm.getMemberValue().toString());
        }
        if (a instanceof NormalAnnotationExpr na) {
            for (String member : members) {
                for (var pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(member)) {
                        return literal(pair.getValue().toString());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Value of an EXPLICITLY-named member ({@code @Ann(member=...)}); null for the single-member/marker forms. Unlike
     * {@link #firstString}, {@code @RequestParam("q")} does NOT answer here — its value is the implicit {@code value},
     * never {@code required}/{@code defaultValue}. (firstString would wrongly return "q" for any member, making
     * {@code @RequestParam("q")} look required=false → a false PARAM_REQUIRED_MISMATCH.)
     */
    static String namedMember(AnnotationExpr a, String member) {
        if (a instanceof NormalAnnotationExpr na) {
            for (var pair : na.getPairs()) {
                if (pair.getNameAsString().equals(member)) {
                    return literal(pair.getValue().toString());
                }
            }
        }
        return null;
    }

    /** All string values of a member, handling the array form {@code @Ann({"A","B"})} (firstString truncates to "A"). */
    static List<String> stringValues(AnnotationExpr a, String... members) {
        Expression e = memberExpr(a, members);
        if (e == null) {
            return List.of();
        }
        List<Expression> elems = e instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(e);
        List<String> out = new ArrayList<>();
        for (Expression x : elems) {
            String v = literal(x.toString());
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    /** True when {@code @JsonIgnore} excludes the property — present and not disabled by {@code @JsonIgnore(false)}
     *  OR {@code @JsonIgnore(value = false)}. The bare marker {@code @JsonIgnore} (no argument) still ignores. */
    static boolean isJsonIgnored(NodeWithAnnotations<?> n) {
        return getAnnotation(n, "JsonIgnore")
                .map(a -> {
                    String v = a instanceof SingleMemberAnnotationExpr sm
                            ? literal(sm.getMemberValue().toString()) : namedMember(a, "value");
                    return !"false".equals(v);   // marker form → v == null → still ignored
                })
                .orElse(false);
    }

    static Expression memberExpr(AnnotationExpr a, String... members) {
        if (a instanceof SingleMemberAnnotationExpr sm) {
            return sm.getMemberValue();
        }
        if (a instanceof NormalAnnotationExpr na) {
            for (String member : members) {
                for (var pair : na.getPairs()) {
                    if (pair.getNameAsString().equals(member)) {
                        return pair.getValue();
                    }
                }
            }
        }
        return null;
    }

    static String literal(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1).trim();
            int comma = s.indexOf(',');
            if (comma >= 0) {
                s = s.substring(0, comma).trim();
            }
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
}
