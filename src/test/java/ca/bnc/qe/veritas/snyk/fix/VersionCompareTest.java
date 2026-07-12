package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The dependency-free version comparator that keeps the fix engine from ever downgrading. */
class VersionCompareTest {

    @Test
    void ordersDottedNumericVersions() {
        assertThat(VersionCompare.compare("2.18.8", "2.21.4")).isNegative();   // 2.18 < 2.21
        assertThat(VersionCompare.compare("2.21.4", "2.18.8")).isPositive();   // 2.21 > 2.18
        assertThat(VersionCompare.compare("2.21.4", "2.21.4")).isZero();
        assertThat(VersionCompare.compare("2.21.10", "2.21.9")).isPositive();  // numeric, not lexical
    }

    @Test
    void treatsAMissingSegmentAsZeroAndIgnoresQualifiers() {
        assertThat(VersionCompare.compare("2.18", "2.18.0")).isZero();
        assertThat(VersionCompare.compare("1.0.9-SNAPSHOT", "1.0.9")).isZero();   // qualifier peeled off
        assertThat(VersionCompare.compare("3.0.0-RC1", "2.9.9")).isPositive();
    }

    @Test
    void atOrAboveDetectsAlreadySatisfiedVersions() {
        assertThat(VersionCompare.atOrAbove("2.21.4", "2.18.8")).isTrue();    // already newer than the "fix" → downgrade
        assertThat(VersionCompare.atOrAbove("2.18.8", "2.18.8")).isTrue();    // already exactly at the fix
        assertThat(VersionCompare.atOrAbove("2.14.0", "2.15.0")).isFalse();   // genuinely below → a real upgrade
        assertThat(VersionCompare.atOrAbove(null, "2.0")).isFalse();
    }
}
