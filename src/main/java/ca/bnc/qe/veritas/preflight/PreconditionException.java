package ca.bnc.qe.veritas.preflight;

import java.util.List;

/**
 * Thrown when a skill is launched without the inputs/config it needs. The message lists each problem and
 * points the user at how to fix it, so a run never starts half-configured.
 */
public class PreconditionException extends RuntimeException {

    private final transient List<String> problems;

    public PreconditionException(String skill, List<String> problems) {
        super("Cannot run '" + skill + "' — " + problems.size() + " precondition(s) not met:\n  - "
                + String.join("\n  - ", problems)
                + "\nHow to configure Veritas: see docs/configuration.md, or run `veritas doctor`.");
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
