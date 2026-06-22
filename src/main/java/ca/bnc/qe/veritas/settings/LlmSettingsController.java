package ca.bnc.qe.veritas.settings;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app LLM engine selection. {@code active} is what the running process uses (it drives whether skill output
 * is real or simulated); {@code desired} is the persisted choice that takes effect on the next restart.
 */
@RestController
@RequestMapping("/api/v1/settings/llm")
public class LlmSettingsController {

    @Value("${veritas.llm.mode:mock}")
    private String activeMode;

    @Value("${veritas.llm.model:claude-sonnet-4.6}")
    private String model;

    private final LlmConfigService service;

    public LlmSettingsController(LlmConfigService service) {
        this.service = service;
    }

    @GetMapping
    public LlmSettingsView get() {
        String desired = service.desiredMode();
        return new LlmSettingsView(
                activeMode,
                desired == null ? activeMode : desired,
                "mock".equalsIgnoreCase(activeMode),   // simulated → results are canned, not real Copilot
                model);
    }

    @PutMapping
    public LlmUpdateResponse update(@RequestBody LlmUpdateRequest req) {
        boolean restart = service.save(req.mode(), activeMode);
        return new LlmUpdateResponse(!restart, restart ? List.of("mode") : List.of());
    }

    /** {@code simulated} = the active engine is the mock (sign-in/connections won't change skill output). */
    public record LlmSettingsView(String active, String desired, boolean simulated, String model) {}

    public record LlmUpdateRequest(String mode) {}

    public record LlmUpdateResponse(boolean applied, List<String> restartRequiredFields) {}
}
