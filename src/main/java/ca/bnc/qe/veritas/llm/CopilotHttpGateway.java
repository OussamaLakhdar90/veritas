package ca.bnc.qe.veritas.llm;

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
            String body = mapper.writeValueAsString(Map.of(
                    "model", model == null ? "" : model,
                    "messages", List.of(Map.of("role", "user", "content", prompt == null ? "" : prompt)),
                    "temperature", 0,
                    "stream", false));
            String resp = corp.postLong(props.getCopilotBase() + "/chat/completions", Map.of(
                    "Authorization", "Bearer " + token,
                    "Accept", "application/json",
                    "X-GitHub-Api-Version", props.getApiVersion(),
                    "editor-version", props.getEditorVersion(),
                    "Copilot-Integration-Id", props.getIntegrationId()), body, "application/json");
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            JsonNode choice = root.path("choices").path(0);
            // Non-streaming: message.content. (If a server streamed, content may live under delta.)
            String content = choice.path("message").path("content").asText(null);
            if (content == null) {
                content = choice.path("delta").path("content").asText("");
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Copilot chat/completions failed: " + e.getMessage(), e);
        }
    }
}
