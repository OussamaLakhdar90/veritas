package ca.bnc.qe.veritas.evolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

class SeverityCatalogEditorTest {

    private final SeverityCatalogEditor editor = new SeverityCatalogEditor();

    // A miniature DiffEngine mirroring the real anchors: a wrapped comma-arm, the fail-safe comment, the default arm,
    // and a two-entry PENDING_CLASSIFICATION allowlist. DESIGN_QUALITY / TEST_BASIS_GAP stand in as "deferred" here.
    private static final String SOURCE = """
            class DiffEngine {
                static final Set<FindingType> PENDING_CLASSIFICATION = Set.of(FindingType.DESIGN_QUALITY, FindingType.TEST_BASIS_GAP);

                static Severity severityOf(FindingType t) {
                    return switch (t) {
                        case OPENAPI_PARSE_ERROR, UNRESOLVED_REF -> Severity.BLOCKER;
                        case MISSING_ENDPOINT, VERB_MISMATCH -> Severity.CRITICAL;
                        // Fail SAFE: a NEW, unclassified FindingType surfaces as UNSPECIFIED.
                        default -> Severity.UNSPECIFIED;
                    };
                }
            }
            """;

    @Test
    void promoteInsertsAStandaloneCaseBeforeDefaultAndDropsTheTypeFromTheAllowlist() {
        String out = editor.promote(SOURCE, FindingType.DESIGN_QUALITY, Severity.MAJOR);

        // A standalone, indented arm on its own line…
        assertThat(out).containsPattern("(?m)^\\s+case DESIGN_QUALITY -> Severity\\.MAJOR;$");
        // …inserted BEFORE the default arm (the default stays last)…
        assertThat(out.indexOf("case DESIGN_QUALITY -> Severity.MAJOR;"))
                .isLessThan(out.indexOf("default -> Severity.UNSPECIFIED;"));
        // …and removed from the allowlist, leaving the sibling untouched.
        assertThat(out).contains("Set.of(FindingType.TEST_BASIS_GAP)")
                .doesNotContain("FindingType.DESIGN_QUALITY");
    }

    @Test
    void promotingTheLastAllowlistedTypeEmptiesTheSet() {
        String single = SOURCE.replace("Set.of(FindingType.DESIGN_QUALITY, FindingType.TEST_BASIS_GAP)",
                "Set.of(FindingType.DESIGN_QUALITY)");
        String out = editor.promote(single, FindingType.DESIGN_QUALITY, Severity.INFO);
        assertThat(out).contains("PENDING_CLASSIFICATION = Set.of();").doesNotContain("FindingType.DESIGN_QUALITY");
    }

    @Test
    void promoteRejectsAnAlreadyClassifiedType() {
        assertThatThrownBy(() -> editor.promote(SOURCE, FindingType.MISSING_ENDPOINT, Severity.MAJOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has an explicit severity");
    }

    @Test
    void promoteRejectsUnspecifiedOrNullSeverity() {
        assertThatThrownBy(() -> editor.promote(SOURCE, FindingType.DESIGN_QUALITY, Severity.UNSPECIFIED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.promote(SOURCE, FindingType.DESIGN_QUALITY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promoteRejectsAnUnrecognizedSource() {
        assertThatThrownBy(() -> editor.promote("class X {}", FindingType.DESIGN_QUALITY, Severity.MAJOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anchors not found");
    }

    @Test
    void theAnchorsResolveAgainstTheRealDiffEngineSource() throws Exception {
        // Guards against the real severityOf shape drifting from the editor's anchors: every current type is already
        // classified, so promoting one must fail with "already classified" (proving the anchors resolved) rather than
        // "anchors not found".
        String real = Files.readString(Path.of("src", "main", "java", "ca", "bnc", "qe", "veritas",
                "engine", "diff", "DiffEngine.java"));
        assertThatThrownBy(() -> editor.promote(real, FindingType.MISSING_ENDPOINT, Severity.INFO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has an explicit severity");
    }

    @Test
    void promoteRejectsANullType() {
        assertThatThrownBy(() -> editor.promote(SOURCE, null, Severity.MAJOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void insertsTheCaseEvenWhenTheSourceHasNoAllowlistLine() {
        String noAllowlist = """
                class DiffEngine {
                    static Severity severityOf(FindingType t) {
                        return switch (t) {
                            case MISSING_ENDPOINT -> Severity.CRITICAL;
                            default -> Severity.UNSPECIFIED;
                        };
                    }
                }
                """;
        String out = editor.promote(noAllowlist, FindingType.DESIGN_QUALITY, Severity.INFO);
        assertThat(out).containsPattern("(?m)^\\s+case DESIGN_QUALITY -> Severity\\.INFO;$");
    }

    @Test
    void removesABareAllowlistTokenWithoutTheFindingTypePrefix() {
        String bare = SOURCE.replace("Set.of(FindingType.DESIGN_QUALITY, FindingType.TEST_BASIS_GAP)",
                "Set.of(DESIGN_QUALITY, TEST_BASIS_GAP)");
        String out = editor.promote(bare, FindingType.DESIGN_QUALITY, Severity.MAJOR);
        assertThat(out).contains("PENDING_CLASSIFICATION = Set.of(TEST_BASIS_GAP)");
    }
}
