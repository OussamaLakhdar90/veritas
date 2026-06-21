package ca.bnc.qe.veritas.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoverageMatcherTest {

    private final CoverageMatcher matcher = new CoverageMatcher();

    @Test
    void matchesByNormalizedTitleAndFlagsGaps() {
        List<CoverageMatcher.Match> matches = matcher.match(
                List.of("Validate create policy", "Validate get policy"),
                List.of(new CoverageMatcher.TitledTest("CIAM-T1", "validate  create   policy")));

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).status()).isEqualTo("MATCHED");
        assertThat(matches.get(0).matchedKey()).isEqualTo("CIAM-T1");
        assertThat(matches.get(1).status()).isEqualTo("GAP");
        assertThat(matches.get(1).matchedKey()).isNull();
    }
}
