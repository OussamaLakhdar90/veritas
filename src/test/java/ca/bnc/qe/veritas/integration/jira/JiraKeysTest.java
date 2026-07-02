package ca.bnc.qe.veritas.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The Jira issue/project key guard that blocks path-injection into the REST URL. */
class JiraKeysTest {

    @Test
    void acceptsRealKeysAndRejectsPathInjectingOnes() {
        assertThat(JiraKeys.issueKey("CIAM-1234")).isEqualTo("CIAM-1234");
        assertThat(JiraKeys.projectKey("CIAM")).isEqualTo("CIAM");
        for (String bad : new String[] {"X/../admin", "A?b=1", "A#frag", "A B", "..", "A%2F", null, ""}) {
            assertThatThrownBy(() -> JiraKeys.issueKey(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
