package ca.bnc.qe.veritas.cli;

import java.util.Map;
import java.util.concurrent.Callable;
import ca.bnc.qe.veritas.skill.SkillRunResult;
import ca.bnc.qe.veritas.skill.SkillRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Phase-0 smoke command: runs the {@code echo} skill end-to-end (one deterministic step + one LLM step)
 * to prove the orchestration framework and the LLM boundary work.
 */
@Component
@Command(name = "echo", description = "Run the echo skill end-to-end (Phase-0 smoke test).")
public class EchoCommand implements Callable<Integer> {

    private final SkillRunner skillRunner;

    @Option(names = {"-t", "--text"}, required = true, description = "Text to echo through the pipeline.")
    private String text;

    public EchoCommand(SkillRunner skillRunner) {
        this.skillRunner = skillRunner;
    }

    @Override
    public Integer call() {
        SkillRunResult result = skillRunner.run("echo", Map.of("text", text));
        System.out.println("Run " + result.runId() + " -> " + result.status());
        System.out.println("Outputs: " + result.outputs());
        System.out.printf("Cost: %.4f premium requests, $%.4f%n",
                result.totalPremiumRequests(), result.totalEstCostUsd());
        return "COMPLETED".equals(result.status()) ? 0 : 1;
    }
}
