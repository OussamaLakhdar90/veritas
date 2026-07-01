package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/**
 * The advisory breaking-change assessment for one dependency upgrade. {@code available=false} means the LLM
 * wasn't consulted (Copilot offline) — the cascade then relies solely on the reactor test build. The verdict never
 * gates the fix by itself; it informs the user and the PR body.
 */
public record BreakingVerdict(boolean available, boolean breaking, int confidence,
                              List<String> reasons, String migrationNotes) {

    public static BreakingVerdict unavailable(String reason) {
        return new BreakingVerdict(false, false, 0, List.of(reason), "");
    }
}
