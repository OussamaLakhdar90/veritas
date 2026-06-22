package ca.bnc.qe.veritas.integration.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfluenceCloudClientTest {

    @Test
    void extractsPageIdFromUrlOrAcceptsBareId() {
        assertThat(ConfluenceCloudClient.pageId("https://wiki.bnc.ca/spaces/IAMAS/pages/1725186990/Some+Title"))
                .isEqualTo("1725186990");
        assertThat(ConfluenceCloudClient.pageId("https://wiki.bnc.ca/spaces/IAMAS/pages/1725186990"))
                .isEqualTo("1725186990");
        assertThat(ConfluenceCloudClient.pageId("1725186990")).isEqualTo("1725186990");
        assertThat(ConfluenceCloudClient.pageId("  42  ")).isEqualTo("42");
    }
}
