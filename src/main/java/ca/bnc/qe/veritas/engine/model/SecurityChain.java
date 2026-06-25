package ca.bnc.qe.veritas.engine.model;

import java.util.List;

/**
 * The ordered authorization rules parsed from a {@code SecurityFilterChain} bean's {@code authorizeHttpRequests}
 * DSL. {@code ambiguous} is set when ANY part of the chain couldn't be parsed to a literal rule (a non-literal
 * matcher argument, a {@code regexMatchers}, an unrecognised authorize terminal like {@code .access(...)}, a
 * missing {@code anyRequest()} default, etc.) — in which case the resolver declines to decide any endpoint and the
 * coarse "centralized security" blind spot is kept. A clean parse lets the resolver assign per-endpoint security
 * for the unambiguous cases.
 */
public record SecurityChain(List<SecurityRule> rules, boolean ambiguous) {

    public SecurityChain {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
