package ca.bnc.qe.veritas.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable state threaded through a skill run: the original inputs, resolved tokens, and the accumulating
 * map of step outputs (keyed by each step's {@code out}).
 */
public class StepContext {

    private final Map<String, Object> inputs;
    private final Map<String, String> tokens;
    private final Map<String, Object> values = new HashMap<>();
    private final String runId;

    public StepContext(Map<String, Object> inputs, Map<String, String> tokens, String runId) {
        this.inputs = inputs == null ? Map.of() : inputs;
        this.tokens = tokens == null ? Map.of() : tokens;
        this.runId = runId;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    public Map<String, String> tokens() {
        return tokens;
    }

    public Map<String, Object> values() {
        return values;
    }

    public String runId() {
        return runId;
    }

    public Object value(String name) {
        return values.get(name);
    }

    public void put(String name, Object value) {
        values.put(name, value);
    }
}
