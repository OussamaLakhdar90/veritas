package ca.bnc.qe.veritas.skill;

/**
 * The contract that enforces "call the LLM only when needed":
 * every pipeline step is one of these kinds.
 */
public enum StepKind {
    /** Pure Java handler — clone, parse, diff, REST calls, file writing, rendering. No LLM. */
    DETERMINISTIC,
    /** A Copilot CLI call — reasoning/generation only. Requires a promptSkill + expectsJson schema. */
    LLM,
    /** A human-approval checkpoint before any outward action (Jira/Xray write, git push/PR). */
    GATE
}
