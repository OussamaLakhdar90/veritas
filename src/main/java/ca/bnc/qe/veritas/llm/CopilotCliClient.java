package ca.bnc.qe.veritas.llm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real gateway: invokes the GitHub Copilot CLI non-interactively
 * ({@code copilot -p <prompt> -s --no-ask-user --model <m>}) with all write/shell tools denied (we only
 * want text back). Active when {@code veritas.llm.mode=copilot}.
 *
 * <p>stdout/stderr are drained on separate threads to avoid pipe-buffer deadlock; a timeout force-kills a
 * hung process. There is no native JSON mode, so callers must extract the fenced json themselves.
 */
@Component
@ConditionalOnProperty(name = "veritas.llm.mode", havingValue = "copilot")
@Slf4j
public class CopilotCliClient implements LlmGateway {

    @Value("${veritas.llm.binary:copilot}")
    private String binary;

    @Value("${veritas.llm.timeout-seconds:180}")
    private long timeoutSeconds;

    /** Bounded retries on a failed/empty CLI invocation (transient process errors, truncated output). */
    @Value("${veritas.llm.max-attempts:2}")
    private int maxAttempts;

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(binary, "--version").redirectErrorStream(true).start();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("Copilot CLI not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String complete(String prompt, String model) {
        int attempts = Math.max(1, maxAttempts);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String out = runOnce(prompt, model);
                if (out != null && !out.isBlank()) {
                    return out;
                }
                last = new IllegalStateException("Copilot CLI returned empty output");
            } catch (RuntimeException e) {
                last = e;
            }
            if (attempt < attempts) {
                log.warn("Copilot CLI attempt {}/{} failed ({}); retrying", attempt, attempts,
                        last == null ? "empty" : last.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("Copilot CLI failed");
    }

    private String runOnce(String prompt, String model) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("-p");
        command.add(prompt);
        command.add("-s");
        command.add("--no-ask-user");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        command.add("--max-autopilot-continues");
        command.add("1");
        // Defence in depth: this is a read-only reasoning call — deny anything that could mutate state.
        for (String denied : List.of("shell", "write", "edit", "create")) {
            command.add("--deny-tool");
            command.add(denied);
        }

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Copilot CLI timed out after " + timeoutSeconds + "s");
            }
            String out = stdout.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Copilot CLI exited " + process.exitValue()
                        + ": " + stderr.get(5, TimeUnit.SECONDS));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Copilot CLI invocation failed: " + e.getMessage(), e);
        }
    }

    private static CompletableFuture<String> readAsync(java.io.InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed reading Copilot CLI stream", e);
            }
            return sb.toString();
        });
    }
}
