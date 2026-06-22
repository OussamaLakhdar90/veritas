package ca.bnc.qe.veritas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class ConnectionsConfigServiceTest {

    private ConnectionsConfigService service(ConnectionsProperties props, Path file) {
        ConnectionsConfigService s = new ConnectionsConfigService(props, new ObjectMapper());
        ReflectionTestUtils.setField(s, "connectionsFile", file);   // don't touch ~/.veritas in tests
        return s;
    }

    @Test
    void updatesLiveFieldsAndPersists(@TempDir Path dir) throws Exception {
        ConnectionsProperties props = new ConnectionsProperties();
        Path file = dir.resolve("connections.json");
        ConnectionsConfigService svc = service(props, file);

        var in = new ConnectionsView(
                new EndpointView("https://bitbucket.bnc", null, "ws", null),
                new EndpointView("https://jira.bnc", null, null, "BEARER"),
                null, null);
        UpdateConnectionsResponse res = svc.update(in);

        // live fields mutated on the singleton (clients read these per-request)
        assertThat(props.getJira().getBaseUrl()).isEqualTo("https://jira.bnc");
        assertThat(props.getJira().getAuthType()).isEqualTo("BEARER");
        assertThat(props.getBitbucket().getWorkspace()).isEqualTo("ws");
        // no edition changed → no restart needed; persisted to the temp file
        assertThat(res.restartRequiredFields()).isEmpty();
        assertThat(Files.readString(file)).contains("jira.bnc").contains("BEARER");
        // round-trips through current()
        assertThat(svc.current().jira().baseUrl()).isEqualTo("https://jira.bnc");
    }

    @Test
    void editionChangeIsFlaggedRestartRequired(@TempDir Path dir) {
        ConnectionsProperties props = new ConnectionsProperties();   // jira edition defaults to SERVER_DC
        ConnectionsConfigService svc = service(props, dir.resolve("connections.json"));

        UpdateConnectionsResponse res = svc.update(new ConnectionsView(null,
                new EndpointView(null, "CLOUD", null, null), null, null));

        assertThat(res.restartRequiredFields()).contains("jira.edition");
        assertThat(props.getJira().getEdition()).isEqualTo("CLOUD");   // persisted; bean re-wires on restart
    }

    @Test
    void rejectsInvalidEdition(@TempDir Path dir) {
        ConnectionsConfigService svc = service(new ConnectionsProperties(), dir.resolve("connections.json"));
        assertThatThrownBy(() -> svc.update(new ConnectionsView(null,
                new EndpointView(null, "BOGUS", null, null), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
