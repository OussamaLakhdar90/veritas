package ca.bnc.qe.veritas.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root CLI command. Subcommands are Spring beans, instantiated through the picocli Spring factory so they
 * can inject services (the skill runner, clients, etc.).
 */
@Component
@Command(
        name = "veritas",
        mixinStandardHelpOptions = true,
        version = "veritas 0.1.0-SNAPSHOT",
        description = "Veritas — API contract validation, ISTQB test management, template-driven test generation.",
        subcommands = { EchoCommand.class, ValidateContractCommand.class, CreateDefectCommand.class,
                TestStrategyCommand.class, CreateTestCasesCommand.class, ReviewTestCasesCommand.class,
                ReleaseTestPlanCommand.class, ImplementTestsCommand.class, CopilotLoginCommand.class,
                DoctorCommand.class }
)
public class VeritasTopCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
