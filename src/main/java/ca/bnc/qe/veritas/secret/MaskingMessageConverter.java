package ca.bnc.qe.veritas.secret;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter that redacts secrets from every rendered log message — registered as {@code %maskedMsg}
 * in {@code logback-spring.xml}. Masks Authorization Bearer/Basic tokens plus any resolved secret value
 * tracked in {@link SecretRegistry}. This is what actually wires {@link LogMasker} into production logging.
 */
public class MaskingMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasker.mask(event.getFormattedMessage(), SecretRegistry.snapshot());
    }
}
