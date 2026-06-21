package ca.bnc.qe.veritas.skill;

/**
 * A deterministic step implementation (Java, no LLM). Each handler is a Spring bean; the step's
 * {@code handler} field names the bean. The returned value is stored under the step's {@code out}.
 */
public interface StepHandler {
    Object handle(StepContext ctx, Step step);
}
