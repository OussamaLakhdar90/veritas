package ca.bnc.qe.veritas.skill;

import java.util.List;
import ca.bnc.qe.veritas.cost.ModelTier;

/**
 * One step in a skill pipeline.
 * <ul>
 *   <li>{@link StepKind#DETERMINISTIC} → requires {@code handler} (a {@link StepHandler} bean name).</li>
 *   <li>{@link StepKind#LLM} → requires {@code promptSkill} + {@code expectsJson}; picks a model from
 *       {@code tier} (or an explicit {@code model} override).</li>
 *   <li>{@link StepKind#GATE} → human approval; no handler/prompt.</li>
 * </ul>
 * {@code inputsFrom} names prior steps' {@code out} values to make available; {@code out} names where this
 * step's result is stored; {@code when} is an optional SpEL guard (evaluated against {@code input}/{@code out}).
 */
public record Step(
        String id,
        StepKind kind,
        String handler,
        String promptSkill,
        String model,
        ModelTier tier,
        List<String> inputsFrom,
        String expectsJson,
        String out,
        String when
) {}
