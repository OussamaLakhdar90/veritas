package ca.bnc.qe.veritas.cli;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Bridges Spring Boot startup to picocli. Active only in CLI mode (property {@code veritas.cli=true}),
 * so it never fires when the app is started in web ({@code serve}) mode.
 */
@Component
@ConditionalOnProperty(name = "veritas.cli", havingValue = "true")
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final VeritasTopCommand topCommand;
    private final IFactory factory;
    private int exitCode;

    public CliRunner(VeritasTopCommand topCommand, IFactory factory) {
        this.topCommand = topCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        this.exitCode = new CommandLine(topCommand, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
