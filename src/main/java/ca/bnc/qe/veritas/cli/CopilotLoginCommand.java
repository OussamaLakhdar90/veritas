package ca.bnc.qe.veritas.cli;

import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.llm.copilot.CopilotAuthService;
import ca.bnc.qe.veritas.llm.copilot.CopilotModelsClient;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/**
 * One-time interactive sign-in for the HTTP Copilot gateway: runs the GitHub device flow, persists the
 * OAuth token, then refreshes live model multipliers. Required before {@code veritas.llm.mode=http} can run.
 */
@Component
@Command(name = "copilot-login", description = "Sign in to GitHub Copilot via the device flow (HTTP gateway).")
public class CopilotLoginCommand implements Callable<Integer> {

    private final CopilotAuthService auth;
    private final CopilotModelsClient models;

    public CopilotLoginCommand(CopilotAuthService auth, CopilotModelsClient models) {
        this.auth = auth;
        this.models = models;
    }

    @Override
    public Integer call() {
        auth.deviceFlow(device -> {
            System.out.println();
            System.out.println("  Open: " + device.verificationUri());
            System.out.println("  Enter code: " + device.userCode());
            System.out.println("  Waiting for you to authorize…");
        });
        System.out.println("Signed in to Copilot. Token stored.");
        try {
            int n = models.refresh();
            System.out.println("Loaded " + n + " model multiplier(s) from Copilot /models.");
        } catch (Exception e) {
            System.out.println("(Models refresh skipped: " + e.getMessage() + ")");
        }
        return 0;
    }
}
