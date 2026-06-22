package ca.bnc.qe.veritas.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.config.ConnectionsProperties.Endpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Reads/updates the integration connection settings in-app. Mutates the {@link ConnectionsProperties} singleton
 * (clients read base-url/auth-type per request, so URL/workspace/auth-type changes apply live) and persists to
 * {@code ~/.veritas/connections.json}, which {@code SettingsEnvironmentPostProcessor} overlays at next startup.
 * Changing {@code edition} is persisted but flagged restart-required (client beans are wired at startup).
 */
@Service
public class ConnectionsConfigService {

    private static final Set<String> EDITIONS = Set.of("SERVER_DC", "CLOUD");

    private final ConnectionsProperties props;
    private final ObjectMapper mapper;
    /** Same path the EnvironmentPostProcessor reads at startup; overridable in tests so they don't touch ~/.veritas. */
    private Path connectionsFile = Path.of(System.getProperty("user.home"), ".veritas", "connections.json");

    public ConnectionsConfigService(ConnectionsProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public ConnectionsView current() {
        return new ConnectionsView(view(props.getBitbucket()), view(props.getJira()),
                view(props.getConfluence()), view(props.getXray()));
    }

    public UpdateConnectionsResponse update(ConnectionsView in) {
        List<String> restart = new ArrayList<>();
        apply("bitbucket", props.getBitbucket(), in.bitbucket(), restart);
        apply("jira", props.getJira(), in.jira(), restart);
        apply("confluence", props.getConfluence(), in.confluence(), restart);
        apply("xray", props.getXray(), in.xray(), restart);
        persist();
        return new UpdateConnectionsResponse(true, restart);
    }

    private void apply(String name, Endpoint ep, EndpointView in, List<String> restart) {
        if (in == null) {
            return;
        }
        if (notBlank(in.edition())) {
            String edition = in.edition().toUpperCase(Locale.ROOT);
            if (!EDITIONS.contains(edition)) {
                throw new IllegalArgumentException("Invalid edition '" + in.edition() + "' for " + name
                        + " — expected one of " + EDITIONS);
            }
            if (!edition.equalsIgnoreCase(ep.getEdition())) {
                restart.add(name + ".edition");   // bean wiring is startup-fixed → surface "restart to activate"
            }
            ep.setEdition(edition);
        }
        if (in.baseUrl() != null) {
            ep.setBaseUrl(blankToNull(in.baseUrl()));
        }
        if (in.workspace() != null) {
            ep.setWorkspace(blankToNull(in.workspace()));
        }
        if (in.authType() != null) {
            ep.setAuthType(blankToNull(in.authType()));
        }
    }

    private void persist() {
        try {
            Map<String, EndpointView> out = new LinkedHashMap<>();
            out.put("bitbucket", view(props.getBitbucket()));
            out.put("jira", view(props.getJira()));
            out.put("confluence", view(props.getConfluence()));
            out.put("xray", view(props.getXray()));
            Files.createDirectories(connectionsFile.getParent());
            Files.writeString(connectionsFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist connection settings: " + e.getMessage(), e);
        }
    }

    private EndpointView view(Endpoint ep) {
        return new EndpointView(ep.getBaseUrl(), ep.getEdition(), ep.getWorkspace(), ep.getAuthType());
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
