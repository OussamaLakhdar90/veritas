package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The thread-local progress-sink seam: armed → notified, unarmed/cleared → no-op, throwing sink → swallowed. */
class LlmCallContextTest {

    @Test
    void progressIsDeliveredOnlyWhileArmed() {
        LlmCallContext ctx = new LlmCallContext();
        AtomicLong seen = new AtomicLong(-1);

        ctx.reportProgress(100);                 // not armed yet → no-op
        assertThat(seen.get()).isEqualTo(-1);

        ctx.armProgressSink(seen::set);
        ctx.reportProgress(42);
        assertThat(seen.get()).isEqualTo(42);

        ctx.clearProgressSink();
        ctx.reportProgress(7);                   // cleared → no-op (the 42 stands)
        assertThat(seen.get()).isEqualTo(42);
    }

    @Test
    void aThrowingProgressSinkIsSwallowedSoItCannotBreakTheLlmCall() {
        LlmCallContext ctx = new LlmCallContext();
        ctx.armProgressSink(chars -> { throw new RuntimeException("db hiccup mid-stream"); });
        assertThatCode(() -> ctx.reportProgress(5)).doesNotThrowAnyException();
        ctx.clearProgressSink();
    }
}
