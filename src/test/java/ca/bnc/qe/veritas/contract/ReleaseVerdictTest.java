package ca.bnc.qe.veritas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ca.bnc.qe.veritas.config.GateProperties;
import ca.bnc.qe.veritas.finding.Confidence;
import ca.bnc.qe.veritas.finding.Finding;
import ca.bnc.qe.veritas.finding.FindingType;
import ca.bnc.qe.veritas.finding.Layer;
import ca.bnc.qe.veritas.finding.Severity;
import org.junit.jupiter.api.Test;

/**
 * The categorical release gate: FAIL on any consumer-breaking finding (or over the blocker/critical caps), WARN on
 * non-breaking additive/documentation drift, PASS when clean. Configurable via {@link GateProperties}. Mirrors the
 * report's bottom line exactly.
 */
class ReleaseVerdictTest {

    private static final GateProperties GATE = new GateProperties();   // defaults: 0/0/0 (zero tolerance)

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
    void aCriticalFindingFails() {
        ReleaseVerdict v = ReleaseVerdict.of(List.of(f(FindingType.MISSING_ENDPOINT, Severity.CRITICAL)), GATE);
        assertThat(v.blocking()).isEqualTo(1);
        assertThat(v.releaseSafe()).isEqualTo("FAIL");
    }

    @Test
    void anySingleBreakingFindingFails() {
        // A breaking MAJOR (a type mismatch changes the response shape) is semver-major → FAIL at the default caps.
        ReleaseVerdict v = ReleaseVerdict.of(List.of(f(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR)), GATE);
        assertThat(v.breaking()).isEqualTo(1);
        assertThat(v.releaseSafe()).isEqualTo("FAIL");
    }

    @Test
    void nonBreakingAdditiveDriftWarns() {
        // Additive documentation drift (fields/status the code has beyond the spec) breaks no consumer → WARN, not FAIL.
        List<Finding> additive = List.of(
                f(FindingType.SCHEMA_FIELD_MISSING, Severity.MINOR),
                f(FindingType.STATUS_CODE_MISSING, Severity.MINOR),
                f(FindingType.PATH_VAR_NAME_MISMATCH, Severity.MINOR));
        ReleaseVerdict v = ReleaseVerdict.of(additive, GATE);
        assertThat(v.breaking()).isZero();
        assertThat(v.releaseSafe()).isEqualTo("WARN");
    }

    @Test
    void aCleanScanPasses() {
        assertThat(ReleaseVerdict.of(List.of(), GATE).releaseSafe()).isEqualTo("PASS");
    }

    @Test
    void disputedFindingsAreExcludedFromGatingButCounted() {
        Finding disputed = f(FindingType.MISSING_ENDPOINT, Severity.CRITICAL).toBuilder().aiDisputed(true).build();
        ReleaseVerdict v = ReleaseVerdict.of(List.of(disputed), GATE);
        assertThat(v.blocking()).isZero();          // needs-attention → excluded from the gate
        assertThat(v.aiDisputed()).isEqualTo(1);    // …but surfaced honestly
        assertThat(v.releaseSafe()).isEqualTo("PASS");
    }

    @Test
    void anUnclassifiedFindingHoldsTheVerdictAtWarnNotPass() {
        // A not-yet-classified (UNSPECIFIED) finding is non-breaking so it can't FAIL, but it must NOT read as a clean
        // PASS — it holds the verdict at WARN until a human classifies it.
        ReleaseVerdict v = ReleaseVerdict.of(List.of(f(FindingType.SPEC_DRIFT, Severity.UNSPECIFIED)), GATE);
        assertThat(v.unspecified()).isEqualTo(1);
        assertThat(v.breaking()).isZero();
        assertThat(v.releaseSafe()).isEqualTo("WARN");
    }

    @Test
    void aUserSeverityOverrideMovesTheGate() {
        // The gate reads EFFECTIVE severity: downgrading a non-breaking MAJOR to INFO drops it out of the WARN tally.
        Finding overridden = f(FindingType.STATUS_CODE_MISSING, Severity.MAJOR).toBuilder()
                .userSeverity(Severity.INFO).build();
        ReleaseVerdict v = ReleaseVerdict.of(List.of(overridden), GATE);
        assertThat(v.major()).isZero();
        assertThat(v.releaseSafe()).isEqualTo("PASS");
    }

    @Test
    void aSeverityOverrideCannotHideABreakingChange() {
        // Even downgraded to INFO, a consumer-breaking MISSING_ENDPOINT still FAILs — breaking-ness is type-derived,
        // not severity-derived, so an override can never green-light a real break.
        Finding overridden = f(FindingType.MISSING_ENDPOINT, Severity.CRITICAL).toBuilder()
                .userSeverity(Severity.INFO).build();
        ReleaseVerdict v = ReleaseVerdict.of(List.of(overridden), GATE);
        assertThat(v.breaking()).isEqualTo(1);
        assertThat(v.releaseSafe()).isEqualTo("FAIL");
    }

    @Test
    void thresholdsAreConfigurable() {
        // Raising max-breaking to 1 downgrades a single breaking finding from FAIL to WARN — the gate is a policy,
        // an auditable threshold, not a magic number.
        GateProperties lenient = new GateProperties();
        lenient.setMaxBreaking(1);
        ReleaseVerdict v = ReleaseVerdict.of(
                List.of(f(FindingType.SCHEMA_FIELD_TYPE_MISMATCH, Severity.MAJOR)), lenient);
        assertThat(v.breaking()).isEqualTo(1);
        assertThat(v.releaseSafe()).isEqualTo("WARN");
    }
}
