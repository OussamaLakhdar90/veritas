package ca.bnc.qe.veritas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The optional disk-backed layer of {@link PromptCache}: identical prompts replay across a JVM restart (so LLM design
 * findings converge run-to-run rather than re-wording), the disk layer is OFF for a bare constructor (protects the
 * developer home dir in unit tests), and concurrent writes of the same key are atomic (one final file, no temps left).
 */
class PromptCacheDiskTest {

    private static PromptCache diskCache(Path dir) {
        PromptCache c = new PromptCache();
        ReflectionTestUtils.setField(c, "diskEnabled", true);
        ReflectionTestUtils.setField(c, "cacheDir", dir.toString());
        c.init();
        return c;
    }

    @Test
    void diskLayerReplaysAcrossInstances(@TempDir Path dir) {
        diskCache(dir).put("model-x", "prompt-y", "RESPONSE-Z");

        // A fresh instance over the SAME dir (a JVM-restart proxy) replays from disk and counts a hit.
        PromptCache fresh = diskCache(dir);
        assertThat(fresh.get("model-x", "prompt-y")).contains("RESPONSE-Z");
        assertThat(fresh.hits()).isEqualTo(1);
        assertThat(fresh.misses()).isZero();

        // The file is named by the SHA-256 key; a different model is a distinct file → a miss.
        assertThat(dir.resolve(PromptCache.key("model-x", "prompt-y") + ".txt")).exists();
        assertThat(fresh.get("model-OTHER", "prompt-y")).isEmpty();
    }

    @Test
    void bareConstructorNeverTouchesDisk() {
        PromptCache c = new PromptCache();   // no Spring wiring → disk default OFF, init() never called
        c.put("m", "p", "r");
        assertThat(c.get("m", "p")).contains("r");                        // memory still works
        assertThat(ReflectionTestUtils.getField(c, "diskDir")).isNull();  // nothing resolved → no home-dir writes
    }

    @Test
    void concurrentWritesOfTheSameKeyLeaveExactlyOneFileAndNoTempFiles(@TempDir Path dir) throws Exception {
        PromptCache c = diskCache(dir);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                c.put("m", "p", "R");
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactly(PromptCache.key("m", "p") + ".txt");   // one final file, no leftover *.tmp
        }
    }
}
