package ca.bnc.qe.veritas.integration;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Shared bounded-timeout request factory for the integration RestClients. Without an explicit connect/read
 * timeout a hung remote host blocks the calling worker thread indefinitely (and the synchronous {@link Retries}
 * never engages), so every client must use a bounded factory. Mirrors the defaults {@link CorpHttp} uses.
 */
public final class HttpFactory {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private HttpFactory() {
    }

    public static ClientHttpRequestFactory bounded() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return factory;
    }
}
