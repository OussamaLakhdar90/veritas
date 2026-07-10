package ca.bnc.qe.veritas.evolve;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import org.springframework.stereotype.Component;

/**
 * Deterministic renderer for a learned severity classification. Given a {@code (FindingType, Severity)} decision it
 * edits the ONE file that owns the engine's severity map — {@code DiffEngine.java} — producing the exact, minimal
 * promotion diff:
 * <ol>
 *   <li>one fresh standalone {@code case NEW_TYPE -> Severity.X;} arm inserted immediately before the unique
 *       {@code default -> Severity.UNSPECIFIED;} arm of {@code severityOf} (never spliced into a wrapped comma-list);</li>
 *   <li>the type removed from the {@code PENDING_CLASSIFICATION} allowlist literal.</li>
 * </ol>
 * The AI supplies only the SEVERITY (its judgment) and a rationale for the PR body; the code edit is deterministic
 * here, so the diff is guaranteed correct, minimal, and lint-clean. It accepts only {@link FindingType} +
 * {@link Severity} enum values, never free text, so a proposal can never inject arbitrary Java. Pure + side-effect
 * free: it transforms and returns source text; the caller writes it into a working copy and opens the PR.
 */
@Component
public class SeverityCatalogEditor {

    private static final String SEVERITY_OF_ANCHOR = "Severity severityOf(FindingType t)";
    private static final String UNSPECIFIED_DEFAULT = "default -> Severity.UNSPECIFIED;";
    private static final String ALLOWLIST_PREFIX = "PENDING_CLASSIFICATION = Set.of(";

    /**
     * @return the edited {@code DiffEngine} source with {@code case type -> Severity.severity;} added to
     *         {@code severityOf} and {@code type} removed from {@code PENDING_CLASSIFICATION}.
     * @throws IllegalArgumentException if the severity is null/{@code UNSPECIFIED}, the {@code severityOf} anchors are
     *         missing (the engine source shape changed), or the type is already classified.
     */
    public String promote(String source, FindingType type, Severity severity) {
        if (type == null) {
            throw new IllegalArgumentException("A FindingType is required.");
        }
        if (severity == null || severity == Severity.UNSPECIFIED) {
            throw new IllegalArgumentException("A real severity (not UNSPECIFIED) is required to classify " + type + ".");
        }
        int methodStart = source.indexOf(SEVERITY_OF_ANCHOR);
        int defaultAt = methodStart < 0 ? -1 : source.indexOf(UNSPECIFIED_DEFAULT, methodStart);
        if (methodStart < 0 || defaultAt < 0) {
            throw new IllegalArgumentException("DiffEngine.severityOf anchors not found — the engine source shape changed.");
        }
        if (alreadyClassified(source, methodStart, defaultAt, type)) {
            throw new IllegalArgumentException(type + " already has an explicit severity in severityOf — this loop only "
                    + "classifies UNSPECIFIED (deferred) types, never reclassifies a mapped one.");
        }
        return removeFromAllowlist(insertCase(source, defaultAt, type, severity), type);
    }

    /** True when the type name already appears as a whole word in the {@code severityOf} switch arms (before default). */
    private static boolean alreadyClassified(String source, int methodStart, int defaultAt, FindingType type) {
        String region = source.substring(methodStart, defaultAt);
        return Pattern.compile("\\b" + Pattern.quote(type.name()) + "\\b").matcher(region).find();
    }

    /** Insert a fresh standalone arm on its own line, right before the {@code default} arm, matching its indentation. */
    private static String insertCase(String source, int defaultAt, FindingType type, Severity severity) {
        int lineStart = source.lastIndexOf('\n', defaultAt) + 1;
        String indent = source.substring(lineStart, defaultAt);
        String arm = indent + "case " + type.name() + " -> Severity." + severity.name() + ";\n";
        return source.substring(0, lineStart) + arm + source.substring(lineStart);
    }

    /** Drop {@code type} from the {@code PENDING_CLASSIFICATION = Set.of(...)} literal (no-op if absent/empty). */
    private static String removeFromAllowlist(String source, FindingType type) {
        int at = source.indexOf(ALLOWLIST_PREFIX);
        if (at < 0) {
            return source;
        }
        int argsStart = at + ALLOWLIST_PREFIX.length();
        int argsEnd = source.indexOf(')', argsStart);
        if (argsEnd < 0) {
            return source;
        }
        List<String> kept = Arrays.stream(source.substring(argsStart, argsEnd).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !isType(s, type))
                .toList();
        return source.substring(0, argsStart) + String.join(", ", kept) + source.substring(argsEnd);
    }

    private static boolean isType(String token, FindingType type) {
        return token.equals(type.name()) || token.equals("FindingType." + type.name());
    }
}
