package ca.bnc.qe.veritas.preflight;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import ca.bnc.qe.veritas.config.ConnectionsProperties;
import ca.bnc.qe.veritas.integration.CorpHttp;
import ca.bnc.qe.veritas.integration.Retries;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.llm.copilot.CopilotProperties;
import ca.bnc.qe.veritas.secret.SecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ConfigDoctorTest {

    @TempDir
    Path tmp;

    private CopilotAuthService unauthenticatedAuth() {
        CopilotProperties cp = new CopilotProperties();
        cp.setTokenFile(tmp.resolve("nope.json").toString());   // no token file → not authenticated
        return new CopilotAuthService(cp, new ObjectMapper(), new CorpHttp(new Retries(RetryTemplate.builder().maxAttempts(1).build())));
    }

    private ConfigDoctor doctor(String mode, SecretProvider secrets) {
        ConfigDoctor d = new ConfigDoctor(secrets, new ConnectionsProperties(), unauthenticatedAuth());
        ReflectionTestUtils.setField(d, "llmMode", mode);
        return d;
    }

    @Test
    void httpModeWithoutSignInFlagsCopilotLogin() {
        List<ConfigDoctor.Check> checks = doctor("http", k -> Optional.empty()).report();
        ConfigDoctor.Check copilot = checks.stream()
                .filter(c -> c.name().startsWith("Copilot")).findFirst().orElseThrow();
        assertThat(copilot.status()).isEqualTo("MISSING");
        assertThat(copilot.remediation()).contains("copilot-login");
    }

    @Test
    void reportsMissingGitTokenAndOkWhenPresent() {
        assertThat(doctor("mock", k -> Optional.empty()).report())
                .anyMatch(c -> c.name().equals("Git access (clone)") && c.status().equals("MISSING"));
        assertThat(doctor("mock", k -> k.equals("GIT_TOKEN") ? Optional.of("tok") : Optional.empty()).report())
                .anyMatch(c -> c.name().equals("Git access (clone)") && c.status().equals("OK"));
    }

    @Test
    void reportsSnykBaseUrlAndTokenSoTheFixWizardHasHonestSnykRows() {
        // The Snyk fix wizard's connection check reads these preflight rows; without them Snyk could be configured
        // (Settings → Test connection green) yet the wizard would report it not-connected. TWO rows (base URL + token)
        // mirror Jira/Bitbucket so a blank base URL can't produce a dishonest "OK" gate.
        List<ConfigDoctor.Check> none = doctor("mock", k -> Optional.empty()).report();
        assertThat(none).anyMatch(c -> c.name().equals("Snyk base URL") && c.status().equals("MISSING"));
        assertThat(none).anyMatch(c -> c.name().equals("Snyk token") && c.status().equals("MISSING"));
        assertThat(doctor("mock", k -> k.equals("SNYK_API_TOKEN") ? Optional.of("tok") : Optional.empty()).report())
                .anyMatch(c -> c.name().equals("Snyk token") && c.status().equals("OK"));

        ConnectionsProperties conns = new ConnectionsProperties();
        conns.getSnyk().setBaseUrl("https://api.snyk.io");
        ConfigDoctor withUrl = new ConfigDoctor(k -> Optional.empty(), conns, unauthenticatedAuth());
        ReflectionTestUtils.setField(withUrl, "llmMode", "mock");
        assertThat(withUrl.report()).anyMatch(c -> c.name().equals("Snyk base URL") && c.status().equals("OK"));
    }
}
