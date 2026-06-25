package ca.bnc.qe.veritas.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The epic→children JQL: edition-aware clause + strict key validation (no JQL injection). */
class EpicChildrenJqlTest {

    @Test
    void cloudUsesTheParentField() {
        assertThat(EpicChildrenJql.forEpic("CLOUD", "CIAM-100", null)).isEqualTo("parent = \"CIAM-100\"");
    }

    @Test
    void serverUsesTheDiscoveredEpicLinkCustomFieldId() {
        assertThat(EpicChildrenJql.forEpic("SERVER_DC", "CIAM-100", "customfield_10001"))
                .isEqualTo("cf[10001] = \"CIAM-100\"");
    }

    @Test
    void serverFallsBackToTheFieldNameWhenTheIdIsUnknown() {
        assertThat(EpicChildrenJql.forEpic("SERVER_DC", "CIAM-100", null)).isEqualTo("\"Epic Link\" = \"CIAM-100\"");
    }

    @Test
    void rejectsAMalformedOrInjectingEpicKey() {
        assertThatThrownBy(() -> EpicChildrenJql.forEpic("CLOUD", "CIAM-1\" OR \"1\"=\"1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EpicChildrenJql.forEpic("CLOUD", "not a key", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EpicChildrenJql.forEpic("CLOUD", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAWellFormedKey() {
        assertThat(EpicChildrenJql.forEpic("CLOUD", "PROJ-123", null)).isEqualTo("parent = \"PROJ-123\"");
    }
}
