package ca.bnc.qe.veritas.codegen.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import ca.bnc.qe.veritas.engine.model.ApiModel;
import ca.bnc.qe.veritas.engine.model.Endpoint;
import org.springframework.stereotype.Component;

/**
 * Reconciles the API model (what should be tested) against an existing test project's inventory (what is tested) into
 * a per-endpoint plan: GAP / STALE / CURRENT / ORPHAN. Fully deterministic — no LLM. The from-scratch case is just the
 * empty-inventory case (every endpoint is a GAP), so one code path serves both "generate" and "refactor".
 *
 * <p>Matching is by endpoint path: an idiomatic Rest-Assured path ({@code "/policies/{id}"}) matches its endpoint
 * exactly; a string-concatenated URL ({@code "/policies/" + id}) can't be reconstructed statically and falls through
 * to a (conservative) GAP. A false GAP merely re-proposes a test the user can uncheck; that's a smaller harm than a
 * false CURRENT that would silently skip a needed test — so the matching deliberately errs toward GAP.
 */
@Component
public class TestReconciler {

    public TestPlan reconcile(String serviceName, ApiModel api, TestInventory inventory) {
        List<Endpoint> endpoints = api == null || api.endpoints() == null ? List.of() : api.endpoints();
        List<TestReference> refs = inventory == null ? List.of() : inventory.references();
        boolean scratch = inventory == null || inventory.isEmpty();
        String mode = scratch ? TestPlan.SCRATCH : TestPlan.REFACTOR;

        List<TestPlanItem> items = new ArrayList<>();
        boolean[] refMatchedAnyEndpoint = new boolean[refs.size()];

        for (Endpoint ep : endpoints) {
            Pattern epRegex = endpointRegex(ep.pathTemplate());
            String coveringFile = null;   // matches path AND (verb matches or is unknown) → CURRENT
            for (int r = 0; r < refs.size(); r++) {
                TestReference ref = refs.get(r);
                if (!epRegex.matcher(normPath(ref.path())).matches()) {
                    continue;
                }
                refMatchedAnyEndpoint[r] = true;   // path matched some endpoint → not an orphan
                if (coveringFile == null && (ref.method() == null || ref.method() == ep.method())) {
                    coveringFile = ref.sourceFile();
                }
            }

            // GAP vs CURRENT only. STALE (the verb/shape of a covered test no longer matches the API) is deferred to
            // the refactor stage: deciding *which* sibling endpoint a phantom-verb reference belongs to is ambiguous
            // statically (a collection path legitimately carries several verbs), so it's judged with the test body in
            // hand rather than guessed here. ORPHAN is handled below.
            String method = ep.method().name();
            if (coveringFile != null) {
                items.add(new TestPlanItem(TestPlanItem.CURRENT, method, ep.pathTemplate(), ep.signature(),
                        coveringFile, "Covered by an existing test — leave as is."));
            } else {
                items.add(new TestPlanItem(TestPlanItem.GAP, method, ep.pathTemplate(), ep.signature(), null,
                        scratch ? "No tests yet — generate one." : "Endpoint not covered — add a test."));
            }
        }

        // ORPHAN: distinct test paths that match no endpoint at all (endpoint may have moved or been removed).
        Set<String> seen = new LinkedHashSet<>();
        for (int r = 0; r < refs.size(); r++) {
            if (refMatchedAnyEndpoint[r]) {
                continue;
            }
            TestReference ref = refs.get(r);
            if (seen.add(normPath(ref.path()))) {
                items.add(new TestPlanItem(TestPlanItem.ORPHAN,
                        ref.method() == null ? null : ref.method().name(), ref.path(), ref.path(), ref.sourceFile(),
                        "A test points at a path not in the current API — review (it may have moved or been removed)."));
            }
        }

        return new TestPlan(serviceName, mode, items, inventory == null ? 0 : inventory.filesScanned());
    }

    /**
     * Regex that a test path must match to be considered a reference to this endpoint: each {@code {param}} segment
     * becomes {@code [^/]+}, the rest is literal, an arbitrary base-path prefix is allowed, and a trailing slash is
     * optional. Case-insensitive.
     */
    private static Pattern endpointRegex(String template) {
        String t = template == null ? "" : template.trim();
        if (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        StringBuilder body = new StringBuilder();
        for (String seg : t.split("/", -1)) {
            if (seg.isEmpty()) {
                continue;
            }
            body.append('/');
            if (seg.startsWith("{") && seg.endsWith("}")) {
                body.append("[^/]+");
            } else {
                body.append(Pattern.quote(seg));
            }
        }
        String path = body.length() == 0 ? "/" : body.toString();
        return Pattern.compile("^.*" + path + "/?$", Pattern.CASE_INSENSITIVE);
    }

    private static String normPath(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim().toLowerCase(Locale.ROOT);
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
