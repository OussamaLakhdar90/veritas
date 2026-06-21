package ca.bnc.qe.veritas.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;

class MaskingMessageConverterTest {

    @Test
    void masksBearerTokensAndRegisteredSecrets() {
        SecretRegistry.remember("S3cretToken-ABCDEF");
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage())
                .thenReturn("calling jira with Authorization: Bearer xyz.123 and token S3cretToken-ABCDEF");

        String out = new MaskingMessageConverter().convert(event);

        assertThat(out).doesNotContain("xyz.123");
        assertThat(out).doesNotContain("S3cretToken-ABCDEF");
        assertThat(out).contains("Bearer ***");
        assertThat(out).contains("***");
    }
}
