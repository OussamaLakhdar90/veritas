package ca.bnc.qe.veritas.skill.handlers;

import java.util.Locale;
import ca.bnc.qe.veritas.skill.Step;
import ca.bnc.qe.veritas.skill.StepContext;
import ca.bnc.qe.veritas.skill.StepHandler;
import org.springframework.stereotype.Component;

/** Trivial deterministic handler used by the Phase-0 echo skill: upper-cases the {@code text} input. */
@Component("upperCaseHandler")
public class UpperCaseHandler implements StepHandler {

    @Override
    public Object handle(StepContext ctx, Step step) {
        Object text = ctx.inputs().get("text");
        return text == null ? "" : text.toString().toUpperCase(Locale.ROOT);
    }
}
