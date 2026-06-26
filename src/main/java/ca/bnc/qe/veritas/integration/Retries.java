package ca.bnc.qe.veritas.integration;

import java.net.ConnectException;
import java.util.function.Supplier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/** Wraps a transient HTTP call with bounded, backed-off retries (rate-limit / 5xx resilience). */
@Component
public class Retries {

    private final RetryTemplate retryTemplate;

    public Retries(RetryTemplate retryTemplate) {
        this.retryTemplate = retryTemplate;
    }

    /**
     * Retry an <b>idempotent</b> read on any transient failure (5xx, read timeout, rate-limit, connect failure) —
     * safe to replay because re-sending a GET produces the same result.
     */
    public <T> T call(Supplier<T> operation) {
        return retryTemplate.execute(ctx -> operation.get());
    }

    /**
     * Retry a <b>non-idempotent write</b> (createIssue/createTest/addStep/comment/link/attach) ONLY when the request
     * definitely never reached the server — i.e. a connection failure ({@link ConnectException}). A 5xx or a read
     * timeout means the server may already have processed the write, so replaying it would create a duplicate Jira
     * ticket / Xray test in the bank's real tracker. On any such non-connect failure we veto further retries (the
     * write surfaces immediately); a connect failure still benefits from the configured backoff/replay.
     */
    public <T> T callWrite(Supplier<T> operation) {
        return retryTemplate.execute(ctx -> {
            try {
                return operation.get();
            } catch (RuntimeException ex) {
                if (!isConnectFailure(ex)) {
                    ctx.setExhaustedOnly();   // request may have been processed — do not re-send it
                }
                throw ex;
            }
        });
    }

    /** True when the cause chain contains a {@link ConnectException} — the connection was never established. */
    public static boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }
}
