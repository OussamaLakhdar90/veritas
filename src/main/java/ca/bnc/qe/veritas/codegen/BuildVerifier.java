package ca.bnc.qe.veritas.codegen;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs the template-derived build/verify command on generated tests to prove they compile.
 * Empty command → SKIPPED. Exit 0 → PASS, else FAIL (output captured for the LLM repair pass).
 */
@Component
@Slf4j
public class BuildVerifier {

    public BuildResult verify(Path workingDir, String command) {
        if (command == null || command.isBlank()) {
            return new BuildResult("SKIPPED", "");
        }
        try {
            Process p = new ProcessBuilder(command.trim().split("\\s+"))
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = drain(p);
            boolean done = p.waitFor(300, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new BuildResult("FAIL", "build timed out");
            }
            return new BuildResult(p.exitValue() == 0 ? "PASS" : "FAIL", tail(out));
        } catch (Exception e) {
            log.warn("Build verify failed to run '{}': {}", command, e.getMessage());
            return new BuildResult("FAIL", e.getMessage());
        }
    }

    private String drain(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private String tail(String s) {
        return s.length() <= 4000 ? s : s.substring(s.length() - 4000);
    }

    public record BuildResult(String status, String output) {}
}
