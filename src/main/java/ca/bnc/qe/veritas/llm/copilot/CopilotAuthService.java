package ca.bnc.qe.veritas.llm.copilot;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.DeviceCode;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.SessionToken;
import ca.bnc.qe.veritas.llm.copilot.CopilotTokens.StoredOAuthToken;
import ca.bnc.qe.veritas.secret.SecretRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Implements the BNC contract-validator's Copilot auth: GitHub OAuth <b>device flow</b> → persisted OAuth
 * token → exchange for a short-lived Copilot <b>session token</b> (cached, refreshed early). The session
 * token is the Bearer for api.githubcopilot.com. See docs/reference-contract-validator.md.
 */
@Component
@Slf4j
public class CopilotAuthService {

    private static final String GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    private final CopilotProperties props;
    private final ObjectMapper mapper;
    private final CorpHttp corp;
    private volatile SessionToken sessionCache;

    public CopilotAuthService(CopilotProperties props, ObjectMapper mapper, CorpHttp corp) {
        this.props = props;
        this.mapper = mapper;
        this.corp = corp;
    }

    /** Run the device flow interactively; {@code onPrompt} receives the user code + verification URL to show. */
    public StoredOAuthToken deviceFlow(Consumer<DeviceCode> onPrompt) {
        try {
            String codeResp = corp.post(props.getGithubBase() + "/login/device/code",
                    Map.of("Accept", "application/json"),
                    "client_id=" + props.getClientId() + "&scope=copilot", "application/x-www-form-urlencoded");
            JsonNode dc = mapper.readTree(codeResp == null ? "{}" : codeResp);
            DeviceCode device = new DeviceCode(dc.path("device_code").asText(), dc.path("user_code").asText(),
                    dc.path("verification_uri").asText(), dc.path("interval").asInt(5), dc.path("expires_in").asInt(900));
            if (onPrompt != null) {
                onPrompt.accept(device);
            }

            long pollMs = Math.max(device.interval() * 1000L, props.getPollIntervalFloorMs());
            for (int attempt = 0; attempt < props.getMaxPollAttempts(); attempt++) {
                Thread.sleep(attempt == 0 ? Math.max(pollMs / 2, 1) : pollMs);
                String body = "client_id=" + props.getClientId() + "&device_code=" + device.deviceCode()
                        + "&grant_type=" + GRANT;
                String pollResp = corp.post(props.getGithubBase() + "/login/oauth/access_token",
                        Map.of("Accept", "application/json"), body, "application/x-www-form-urlencoded");
                JsonNode r = mapper.readTree(pollResp == null || pollResp.isBlank() ? "{}" : pollResp);
                String error = r.path("error").asText("");
                if ("authorization_pending".equals(error)) {
                    continue;
                }
                if ("slow_down".equals(error)) {
                    Thread.sleep(pollMs);
                    continue;
                }
                if ("expired_token".equals(error)) {
                    throw new IllegalStateException("Device code expired. Please run copilot-login again.");
                }
                if (r.hasNonNull("access_token")) {
                    StoredOAuthToken token = new StoredOAuthToken(r.path("access_token").asText(),
                            r.path("token_type").asText("bearer"), r.path("scope").asText("copilot"));
                    saveOAuth(token);
                    return token;
                }
                if (!error.isBlank()) {
                    throw new IllegalStateException("OAuth error: " + error);
                }
            }
            throw new IllegalStateException("Device flow timed out after " + props.getMaxPollAttempts() + " attempts.");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Device flow interrupted", ie);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Device flow failed: " + e.getMessage(), e);
        }
    }

    /** Exchange the stored OAuth token for a Copilot session token (cached; refreshed 5 min before expiry). */
    public String getSessionToken() {
        SessionToken cached = sessionCache;
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minusSeconds(300))) {
            return cached.token();
        }
        StoredOAuthToken oauth = storedOAuth();
        if (oauth == null) {
            throw new IllegalStateException("Not authenticated. Run `veritas copilot-login` first.");
        }
        try {
            String resp = corp.get(props.getApiBase() + "/copilot_internal/v2/token",
                    Map.of("Authorization", "token " + oauth.accessToken(), "Accept", "application/json"));
            JsonNode r = mapper.readTree(resp == null ? "{}" : resp);
            if (!r.hasNonNull("token")) {
                throw new IllegalStateException("Copilot token exchange failed; sign out and sign in again.");
            }
            Instant exp = r.hasNonNull("expires_at")
                    ? Instant.ofEpochSecond(r.path("expires_at").asLong())
                    : Instant.now().plusSeconds(25 * 60);
            SessionToken token = new SessionToken(r.path("token").asText(), exp);
            SecretRegistry.remember(token.token());
            sessionCache = token;
            return token.token();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Copilot token exchange failed: " + e.getMessage(), e);
        }
    }

    public boolean isAuthenticated() {
        return storedOAuth() != null;
    }

    public StoredOAuthToken storedOAuth() {
        Path file = Path.of(props.getTokenFile());
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode t = n.has("oauth_token") ? n.get("oauth_token") : n;
            if (!t.hasNonNull("access_token")) {
                return null;
            }
            SecretRegistry.remember(t.path("access_token").asText());
            return new StoredOAuthToken(t.path("access_token").asText(),
                    t.path("token_type").asText("bearer"), t.path("scope").asText("copilot"));
        } catch (Exception e) {
            log.warn("Could not read Copilot token file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void saveOAuth(StoredOAuthToken token) throws Exception {
        SecretRegistry.remember(token.accessToken());
        var node = mapper.createObjectNode();
        var inner = node.putObject("oauth_token");
        inner.put("access_token", token.accessToken());
        inner.put("token_type", token.tokenType());
        inner.put("scope", token.scope());
        Path file = Path.of(props.getTokenFile());
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, mapper.writeValueAsString(node), StandardCharsets.UTF_8);
        try {   // owner-only on POSIX; Windows has no POSIX perms → rely on the user profile dir ACL
            Files.setPosixFilePermissions(file, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // not POSIX (e.g. Windows) — token lives under the user home dir
        }
    }
}
