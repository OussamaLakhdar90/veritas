package ca.bnc.qe.veritas.evidence.feature;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The tiny morphological stemmer: plural/singular fold, but leave -ss/-us words alone. */
class StemmerTest {

    @Test
    void foldsSimplePlurals() {
        assertThat(Stemmer.stem("policies")).isEqualTo("policy");
        assertThat(Stemmer.stem("policy")).isEqualTo("policy");       // idempotent
        assertThat(Stemmer.stem("logins")).isEqualTo("login");
        assertThat(Stemmer.stem("boxes")).isEqualTo("box");
        assertThat(Stemmer.stem("accounts")).isEqualTo("account");
    }

    @Test
    void leavesNonPluralAndAmbiguousEndingsAlone() {
        assertThat(Stemmer.stem("status")).isEqualTo("status");   // -us, not a plural
        assertThat(Stemmer.stem("class")).isEqualTo("class");     // -ss, not a plural
        assertThat(Stemmer.stem("api")).isEqualTo("api");         // too short
        assertThat(Stemmer.stem(null)).isEmpty();
    }
}
