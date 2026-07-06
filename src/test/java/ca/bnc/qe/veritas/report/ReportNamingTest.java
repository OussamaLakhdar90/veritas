package ca.bnc.qe.veritas.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import ca.bnc.qe.veritas.persistence.Scan;
import org.junit.jupiter.api.Test;

/** The on-disk report name is readable (service + model + UTC date) and always a safe single filename token. */
class ReportNamingTest {

    private static Scan scan(String service, String model, Instant started) {
        Scan s = new Scan();
        s.setServiceName(service);
        s.setModel(model);
        s.setStartedAt(started);
        return s;
    }

    @Test
    void buildsAReadableNameFromServiceModelAndUtcDate() {
        String name = ReportNaming.baseName(scan("ciam-policies", "claude-opus-4-8",
                Instant.parse("2026-06-30T14:15:22Z")));
        assertThat(name).isEqualTo("contract-report-ciam-policies-claude-opus-4-8-2026-06-30_141522");
    }

    @Test
    void slugsUnsafeCharactersAndFallsBackOnBlanks() {
        // spaces / slashes / dots collapse to '-'; a null model -> "no-ai"; a null start time -> "undated".
        String name = ReportNaming.baseName(scan("Ciam Policies / v2.1", null, null));
        assertThat(name).isEqualTo("contract-report-Ciam-Policies-v2-1-no-ai-undated");
    }

    @Test
    void blankServiceFallsBackToService() {
        assertThat(ReportNaming.baseName(scan("   ", "m", Instant.parse("2026-01-01T00:00:00Z"))))
                .isEqualTo("contract-report-service-m-2026-01-01_000000");
    }

    @Test
    void neverContainsAPathSeparatorOrDotDot() {
        String name = ReportNaming.baseName(scan("../../etc/passwd", "../x", Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(name).doesNotContain("/").doesNotContain("\\").doesNotContain("..");
    }

    @Test
    void correctedSpecNameIsKeyedOnTheScanId() {
        // The corrected-YAML filename the writer + the report link + the serving endpoint all share — keyed on the
        // (unique) scan id so co-located scans in out/ never collide.
        Scan s = scan("ciam-policies", "m", Instant.parse("2026-01-01T00:00:00Z"));
        s.setId("abc-123");
        assertThat(ReportNaming.correctedSpecName(s)).isEqualTo("openapi.corrected-abc-123.yaml");
    }

    @Test
    void correctedSpecNameFallsBackAndStaysSafeWhenIdIsBlankOrUnsafe() {
        Scan blank = scan("svc", "m", null);
        blank.setId("   ");
        assertThat(ReportNaming.correctedSpecName(blank)).isEqualTo("openapi.corrected-spec.yaml");
        Scan evil = scan("svc", "m", null);
        evil.setId("../../etc/passwd");
        assertThat(ReportNaming.correctedSpecName(evil))
                .doesNotContain("/").doesNotContain("\\").doesNotContain("..")
                .startsWith("openapi.corrected-").endsWith(".yaml");
    }
}
