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
    private final LlmCallContext callContext;

    public CopilotHttpGateway(CopilotAuthService auth, CopilotProperties props, ObjectMapper mapper, CorpHttp corp,
                              LlmCallContext callContext) {
        this.auth = auth;
        this.props = props;
        this.mapper = mapper;
        this.corp = corp;
        this.callContext = callContext;
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
                    "stream", true,
                    "stream_options", Map.of("include_usage", true)));   // ask for a final usage chunk (real token counts)
            log.info("Copilot chat: model={}, prompt {} chars (streaming)", model, prompt == null ? 0 : prompt.length());
            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + token,
                    "Accept", "text/event-stream",
                    "X-GitHub-Api-Version", props.getApiVersion(),
                    "editor-version", props.getEditorVersion(),
                    "Copilot-Integration-Id", props.getIntegrationId());

            StringBuilder content = new StringBuilder();
            List<String> rawLines = new ArrayList<>();
            long[] usage = {-1, -1};   // [promptTokens, completionTokens]; -1 = the provider didn't report usage
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
                    JsonNode root = mapper.readTree(data);
                    captureUsage(root, usage);   // the final chunk carries usage (choices may be empty there)
                    JsonNode choice = root.path("choices").path(0);
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
                JsonNode root = mapper.readTree(joined.isEmpty() ? "{}" : joined);
                captureUsage(root, usage);
                reportUsage(usage);
                JsonNode choice = root.path("choices").path(0);
                String c = choice.path("message").path("content").asText(null);
                if (c == null) {
                    c = choice.path("delta").path("content").asText("");
                }
                log.info("Copilot chat: received {} chars (non-streamed)", c == null ? 0 : c.length());
                return c;
            }
            reportUsage(usage);
            log.info("Copilot chat: received {} chars (streamed)", content.length());
            return content.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Copilot chat/completions failed: " + e.getMessage(), e);
        }
    }

    /** Capture the OpenAI {@code usage} object ({@code prompt_tokens}/{@code completion_tokens}) into the holder. */
    private static void captureUsage(JsonNode root, long[] usage) {
        JsonNode u = root.path("usage");
        if (u.isObject() && u.hasNonNull("prompt_tokens")) {
            usage[0] = u.path("prompt_tokens").asLong(0);
            usage[1] = u.path("completion_tokens").asLong(0);
        }
    }

    /** Hand the real token counts to the cost recorder (via the thread-local context) when the provider reported them. */
    private void reportUsage(long[] usage) {
        if (usage[0] >= 0) {
            callContext.markUsage(usage[0], usage[1]);
        }
    }
}
