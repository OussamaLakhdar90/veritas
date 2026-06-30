package ca.bnc.qe.veritas.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * normPath collapses path variables with a brace-balanced scan, so a regex var with a quantifier brace
 * ({id:[0-9]{2}}) collapses to the same {} key as the spec's plain {id} — the old \{[^}]*\} left a stray '}'.
 */
class DiffEngineNormPathTest {

    @Test
    void brace_quantifier_regex_var_collapses_like_a_plain_var() {
        assertThat(DiffEngine.normPath("/u/{id:[0-9]{2}}")).isEqualTo("/u/{}");
        assertThat(DiffEngine.normPath("/u/{id}")).isEqualTo("/u/{}");
        assertThat(DiffEngine.normPath("/a/{name:[a-z]+}/b/{n:[0-9]{2,5}}")).isEqualTo("/a/{}/b/{}");
        assertThat(DiffEngine.normPath("/x//y")).isEqualTo("/x/y");      // double slash still collapses
        assertThat(DiffEngine.normPath("/Users/{id}")).isEqualTo("/users/{}");
    }
}
