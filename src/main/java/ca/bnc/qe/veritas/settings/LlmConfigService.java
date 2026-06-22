package ca.bnc.qe.veritas.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Reads/updates the LLM engine selection in-app. The active gateway is chosen at startup via
 * {@code @ConditionalOnProperty veritas.llm.mode}, so a change is persisted to {@code ~/.veritas/llm.json}
 * (which {@code SettingsEnvironmentPostProcessor} overlays before binding on the next start) and flagged
 * restart-required — mirroring how a connection {@code edition} change is handled.
 */
@Service
public class LlmConfigService {

    /** mock = simulated responses; http = Copilot device-flow HTTP API; copilot = the Copilot CLI binary. */
    public static final Set<String> MODES = Set.of("mock", "http", "copilot");

    private final ObjectMapper mapper;
    /** Same path the EnvironmentPostProcessor reads; overridable in tests so they don't touch ~/.veritas. */
    private Path llmFile = Path.of(System.getProperty("user.home"), ".veritas", "llm.json");

    public LlmConfigService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The desired mode persisted in the file (may differ from the active/effective mode until a restart), or null. */
    @SuppressWarnings("unchecked")
    public String desiredMode() {
        try {
            if (!Files.exists(llmFile)) {
                return null;
            }
            Object mode = mapper.readValue(Files.readString(llmFile), Map.class).get("mode");
            return mode == null ? null : mode.toString();
        } catch (Exception e) {
            return null;   // corrupt/unreadable → treat as "no preference"
        }
    }

    /** Persist the desired engine; returns whether it differs from the running mode (i.e. a restart is needed). */
    public boolean save(String mode, String activeMode) {
        String m = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
        if (!MODES.contains(m)) {
            throw new IllegalArgumentException("Invalid LLM mode '" + mode + "' — expected one of " + MODES);
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", m);
            Files.createDirectories(llmFile.getParent());
            Files.writeString(llmFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist LLM settings: " + e.getMessage(), e);
        }
        return !m.equalsIgnoreCase(activeMode);
    }
}
