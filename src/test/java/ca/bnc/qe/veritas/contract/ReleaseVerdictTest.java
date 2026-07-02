package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

/** The one shared verdict: FAIL on blocking, PASS at/above the gate OR when all drift is additive,
 *  WARN when sub-gate findings would break a consumer. Mirrors the report's bottom line exactly. */
class ReleaseVerdictTest {

    private static Finding f(FindingType type, Severity sev) {
        return Finding.builder()
                .findingId(type.name() + sev.name())
                .type(type)
                .layer(Layer.L4)
                .severity(sev)
                .confidence(Confidence.HIGH)
                .origin("DETERMINISTIC")
                .service("svc")
                .specSource("spec")
                .endpoint("GET /x")
                .summary("s")
                .build();
    }

    @Test
    void blockingFindingFailsRegardlessOfScore() {
        ReleaseVerdict v = ReleaseVerdict.of(List.of(f(FindingType.MISSING_ENDPOINT, Severity.CRITICAL)));
        assertThat(v.blocking()).isEqualTo(1);
        assertThat(v.releaseSafe()).isEqualTo("FAIL");
    }

    @Test
    void subGateButAllAdditiveIsStillReleaseSafe() {
        // 5 × MINOR (-3) = score 85 < 90, but every finding is documentation drift → PASS (PR #230 policy).
        List<Finding> additive = List.of(
                f(FindingType.SCHEMA_FIELD_MISSING, Severity.MINOR), f(FindingType.STATUS_CODE_MISSING, Severity.MINOR),
                f(FindingType.SCHEMA_FIELD_MISSING, Severity.MINOR), f(FindingType.STATUS_CODE_MISSING, Severity.MINOR),
                f(FindingType.PATH_VAR_NAME_MISMATCH, Severity.MINOR));
        ReleaseVerdict v = ReleaseVerdict.of(additive);
        assertThat(v.score()).isLessThan(90);
        assertThat(v.allNonBreaking()).isTrue();
        assertThat(v.releaseSafe()).isEqualTo("PASS");
    }

    @Test
    void subGateWithBreakingFindingsHolds() {
        // 2 × MAJOR (-8) = 84 < 90 and the type mismatch breaks a consumer → WARN (hold for fixes).
        ReleaseVerdict v = ReleaseVerdict.of(List.of(
                f(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR),
                f(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR)));
        assertThat(v.score()).isLessThan(90);
        assertThat(v.breaking()).isEqualTo(2);
        assertThat(v.releaseSafe()).isEqualTo("WARN");
    }

    @Test
    void atOrAboveTheGatePasses() {
        ReleaseVerdict v = ReleaseVerdict.of(List.of(f(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR)));
        assertThat(v.score()).isGreaterThanOrEqualTo(90);
        assertThat(v.releaseSafe()).isEqualTo("PASS");
    }

    @Test
    void disputedFindingsAreExcludedFromGatingButCounted() {
        Finding disputed = f(FindingType.MISSING_ENDPOINT, Severity.CRITICAL).toBuilder().aiDisputed(true).build();
        ReleaseVerdict v = ReleaseVerdict.of(List.of(disputed));
        assertThat(v.blocking()).isZero();          // needs-attention → excluded from the gate
        assertThat(v.aiDisputed()).isEqualTo(1);    // …but surfaced honestly
        assertThat(v.releaseSafe()).isEqualTo("PASS");
    }
}
