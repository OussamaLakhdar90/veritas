package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The allow-list that keeps XML metacharacters out of any Maven token written into a built pom. */
class MavenTokensTest {

    @Test
    void acceptsRealMavenVersionsAndCoordinates() {
        for (String v : new String[] {"1.0.10", "1.7.15.1", "2.0.0-SNAPSHOT", "3.18.0", "2.0.0.RELEASE"}) {
            assertThat(MavenTokens.version(v)).isEqualTo(v);
            assertThat(MavenTokens.isSafe(v)).isTrue();
        }
        assertThat(MavenTokens.coordinate("com.fasterxml.jackson.core")).isEqualTo("com.fasterxml.jackson.core");
        assertThat(MavenTokens.coordinate("jackson-databind")).isEqualTo("jackson-databind");
    }

    @Test
    void rejectsXmlMetacharactersWhitespaceAndEmpty() {
        for (String bad : new String[] {
                "1.0</version><build>", "a<b", "a>b", "a&b", "a\"b", "a'b", "1 0", "", null}) {
            assertThat(MavenTokens.isSafe(bad)).isFalse();
            assertThatThrownBy(() -> MavenTokens.version(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
