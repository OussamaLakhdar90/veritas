package ca.bnc.qe.veritas.llm.copilot;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the HTTP Copilot gateway (the device-flow + session-token approach the BNC contract-validator
 * app uses — see docs/reference-contract-validator.md). All endpoints are overridable for the BNC network.
 */
@Component
@ConfigurationProperties(prefix = "veritas.copilot")
@Getter
@Setter
public class CopilotProperties {

    /** Public Copilot client id (copilot.vim / Neovim) — the same one the reference app uses. */
    private String clientId = "Iv1.b507a08c87ecfe98";
    private String githubBase = "https://github.com";
    private String apiBase = "https://api.github.com";
    private String copilotBase = "https://api.githubcopilot.com";
    /** Where the persisted OAuth token lives (written by `veritas copilot-login`) — under the user home dir. */
    private String tokenFile = System.getProperty("user.home", ".") + "/.veritas/copilot-auth.json";
    private String editorVersion = "vscode/1.100.0";
    private String integrationId = "vscode-chat";
    private String apiVersion = "2025-04-01";
    /** Device-flow polling floor (ms) and cap — small values let tests run fast. */
    private long pollIntervalFloorMs = 5000;
    private int maxPollAttempts = 120;
}
