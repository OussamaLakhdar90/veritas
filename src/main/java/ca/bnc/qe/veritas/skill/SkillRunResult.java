package ca.bnc.qe.veritas.skill;

import java.util.Map;

/** The outcome of a skill run: persisted run id, terminal status, collected outputs, and rolled-up LLM cost. */
public record SkillRunResult(
        String runId,
        String status,
        Map<String, Object> outputs,
        double totalPremiumRequests,
        double totalEstCostUsd
) {}
