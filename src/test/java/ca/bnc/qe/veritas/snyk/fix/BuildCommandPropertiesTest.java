package ca.bnc.qe.veritas.snyk.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The operator-set per-app build-command override lookup ({@code veritas.snyk.fix.build-commands}). */
class BuildCommandPropertiesTest {

    private final BuildCommandProperties props = new BuildCommandProperties();

    @Test
    void returnsTheConfiguredCommandTrimmedAndMatchesTheAppIdCaseInsensitively() {
        props.getBuildCommands().put("APP7571", "  mvn -q -B -DsuiteXmlFile=src/test/resources/t.xml test  ");
        assertThat(props.override("app7571"))   // lower-case lookup against an upper-case key
                .contains("mvn -q -B -DsuiteXmlFile=src/test/resources/t.xml test");   // trimmed
    }

    @Test
    void isEmptyForAnUnknownApp() {
        props.getBuildCommands().put("APP7571", "mvn -q -B test");
        assertThat(props.override("APP9999")).isEmpty();
    }

    @Test
    void isEmptyWhenNothingIsConfigured() {
        assertThat(props.override("APP7571")).isEmpty();
    }

    @Test
    void treatsABlankOrNullConfiguredValueAsNoOverride() {
        props.getBuildCommands().put("APP7571", "   ");
        props.getBuildCommands().put("APP7572", null);
        assertThat(props.override("APP7571")).isEmpty();   // blank → no override
        assertThat(props.override("APP7572")).isEmpty();   // null  → no override
    }

    @Test
    void isEmptyForANullAppId() {
        props.getBuildCommands().put("APP7571", "mvn -q -B test");
        assertThat(props.override(null)).isEmpty();
    }
}
