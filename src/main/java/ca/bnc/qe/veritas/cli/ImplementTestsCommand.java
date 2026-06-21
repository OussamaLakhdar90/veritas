package ca.bnc.qe.veritas.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.codegen.CodegenService;
import ca.bnc.qe.veritas.persistence.CodegenRun;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Generate automated tests by following a user-supplied MD template (writes files; PR is live-validated). */
@Component
@Command(name = "implement-tests", description = "Generate automated tests from a template (template-driven).")
public class ImplementTestsCommand implements Callable<Integer> {

    private final CodegenService codegenService;

    @Option(names = {"-n", "--name"}, required = true, description = "Service name.")
    private String name;

    @Option(names = "--service-repo", required = true, description = "Local path to the service under test.")
    private Path serviceRepo;

    @Option(names = "--template", required = true, description = "Path to the MD test-generation template.")
    private Path template;

    @Option(names = "--output-dir", required = true, description = "Directory to write generated tests into.")
    private Path outputDir;

    public ImplementTestsCommand(CodegenService codegenService) {
        this.codegenService = codegenService;
    }

    @Override
    public Integer call() {
        CodegenRun run = codegenService.generate(name, serviceRepo, template, outputDir, "local");
        System.out.println("Codegen run " + run.getId() + " -> build " + run.getBuildStatus());
        System.out.println("Output: " + run.getOutputRepo());
        System.out.printf("Est. LLM cost: $%.4f%n", run.getEstCostUsd());
        return 0;
    }
}
