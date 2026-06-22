package ca.bnc.qe.veritas.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.llm.copilot.CopilotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real gateway that talks to the Copilot <b>HTTP API</b> (the approach the BNC contract-validator app uses),
 * not the {@code copilot} CLI. Active when {@code veritas.llm.mode=http}. Auth (device flow + session token)
 * is handled by {@link CopilotAuthService}; this class issues an OpenAI-compatible, non-streaming
 * {@code chat/completions} request and returns the assistant message text.
 */
@Component
@ConditionalOnProperty(name = "veritas.llm.mode", havingValue = "http")
@Slf4j
public class CopilotHttpGateway implements LlmGateway {

    private final CopilotAuthService auth;
    private final CopilotProperties props;
    private final ObjectMapper mapper;
    private final CorpHttp corp;

    public CopilotHttpGateway(CopilotAuthService auth, CopilotProperties props, ObjectMapper mapper, CorpHttp corp) {
        this.auth = auth;
        this.props = props;
        this.mapper = mapper;
        this.corp = corp;
    }

    @Override
    public boolean isAvailable() {
        try {
            auth.getSessionToken();
            return true;
        } catch (Exception e) {
            log.warn("Copilot HTTP gateway not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String complete(String prompt, String model) {
        String token = auth.getSessionToken();
        try {
            // Stream the response (SSE). Generating a full corrected spec + explanations takes minutes, and a
            // single whole-body read would trip the read timeout; streaming makes the timeout a per-chunk
            // budget. Matches the BNC contract-validator reference app's Copilot integration.
            String body = mapper.writeValueAsString(Map.of(
                    "model", model == null ? "" : model,
                    "messages", List.of(Map.of("role", "user", "content", prompt == null ? "" : prompt)),
                    "temperature", 0,
                    "stream", true));
            log.info("Copilot chat: model={}, prompt {} chars (streaming)", model, prompt == null ? 0 : prompt.length());
            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + token,
                    "Accept", "text/event-stream",
                    "X-GitHub-Api-Version", props.getApiVersion(),
                    "editor-version", props.getEditorVersion(),
                    "Copilot-Integration-Id", props.getIntegrationId());

            StringBuilder content = new StringBuilder();
            List<String> rawLines = new ArrayList<>();
            corp.postStreamLines(props.getCopilotBase() + "/chat/completions", headers, body, "application/json", line -> {
                rawLines.add(line);
                String t = line.trim();
                if (!t.startsWith("data:")) {
                    return;   // SSE comment / blank keep-alive line
                }
                String data = t.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                try {
                    JsonNode choice = mapper.readTree(data).path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText(null);
                    if (delta != null) {
                        content.append(delta);
                    } else {
                        String msg = choice.path("message").path("content").asText(null);
                        if (msg != null) {
                            content.append(msg);
                        }
                    }
                } catch (Exception ignore) {
                    // a non-JSON data line (e.g. a server keep-alive) — skip it
                }
            });

            if (content.length() == 0) {
                // The server answered with a single JSON object instead of an SSE stream — parse the whole body.
                String joined = String.join("\n", rawLines).trim();
                JsonNode choice = mapper.readTree(joined.isEmpty() ? "{}" : joined).path("choices").path(0);
                String c = choice.path("message").path("content").asText(null);
                if (c == null) {
                    c = choice.path("delta").path("content").asText("");
                }
                log.info("Copilot chat: received {} chars (non-streamed)", c == null ? 0 : c.length());
                return c;
            }
            log.info("Copilot chat: received {} chars (streamed)", content.length());
            return content.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Copilot chat/completions failed: " + e.getMessage(), e);
        }
    }
}
