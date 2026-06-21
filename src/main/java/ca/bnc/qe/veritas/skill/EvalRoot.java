package ca.bnc.qe.veritas.skill;

import java.util.Map;

/**
 * Root object for SpEL {@code when} guards. With a {@code MapAccessor} registered, expressions can read
 * {@code input.foo} and {@code out.bar} directly.
 */
public class EvalRoot {

    private final Map<String, Object> input;
    private final Map<String, Object> out;

    public EvalRoot(Map<String, Object> input, Map<String, Object> out) {
        this.input = input;
        this.out = out;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getOut() {
        return out;
    }
}
