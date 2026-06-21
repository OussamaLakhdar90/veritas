package ca.bnc.qe.veritas.llm.copilot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.cost.LiveModelMultipliers;
import ca.bnc.qe.veritas.integration.CorpHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fetches Copilot models from {@code api.githubcopilot.com/models} and feeds the authoritative
 * {@code billing.multiplier} per model into {@link LiveModelMultipliers}, so cost reflects real billing.
 * Mirrors the reference app's {@code fetchCopilotModels} / {@code parseBilling}.
 */
@Component
@Slf4j
public class CopilotModelsClient {

    private final CopilotAuthService auth;
    private final CopilotProperties props;
    private final ObjectMapper mapper;
    private final CorpHttp corp;
    private final LiveModelMultipliers live;

    public CopilotModelsClient(CopilotAuthService auth, CopilotProperties props, ObjectMapper mapper,
                               CorpHttp corp, LiveModelMultipliers live) {
        this.auth = auth;
        this.props = props;
        this.mapper = mapper;
        this.corp = corp;
        this.live = live;
    }

    /** Refresh live multipliers from the models endpoint; returns the number of models parsed. */
    public int refresh() {
        String token = auth.getSessionToken();
        try {
            String resp = corp.get(props.getCopilotBase() + "/models", Map.of(
                    "Authorization", "Bearer " + token,
                    "Accept", "application/json",
                    "X-GitHub-Api-Version", props.getApiVersion(),
                    "editor-version", props.getEditorVersion(),
                    "Copilot-Integration-Id", props.getIntegrationId()));
            JsonNode root = mapper.readTree(resp == null ? "{}" : resp);
            JsonNode data = root.has("data") ? root.get("data") : root;
            Map<String, Double> mult = new HashMap<>();
            Set<String> premium = new HashSet<>();
            for (JsonNode m : data) {
                String id = m.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                Double multiplier = parseMultiplier(m);
                if (multiplier != null) {
                    mult.put(id, multiplier);
                }
                if (isPremium(m)) {
                    premium.add(id);
                }
            }
            live.update(mult, premium);
            log.info("Refreshed {} Copilot model multiplier(s) from /models", mult.size());
            return mult.size();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Copilot /models fetch failed: " + e.getMessage(), e);
        }
    }

    /** GitHub's multiplier may be a number OR a numeric string, under billing or model_billing (varies by version). */
    private Double parseMultiplier(JsonNode model) {
        JsonNode billing = model.has("billing") ? model.get("billing") : model.path("model_billing");
        if (billing == null || !billing.isObject()) {
            return null;
        }
        JsonNode mult = billing.path("multiplier");
        if (mult.isNumber()) {
            return mult.asDouble();
        }
        if (mult.isTextual()) {
            try {
                return Double.parseDouble(mult.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isPremium(JsonNode model) {
        JsonNode billing = model.has("billing") ? model.get("billing") : model.path("model_billing");
        if (billing != null && billing.path("is_premium").asBoolean(false)) {
            return true;
        }
        Double m = parseMultiplier(model);
        return m != null && m > 0.0;
    }
}
