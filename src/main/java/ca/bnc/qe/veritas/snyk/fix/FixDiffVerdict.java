package ca.bnc.qe.veritas.snyk.fix;

/**
 * The advisory AI read of what a fix actually changed. {@code available=false} means the LLM wasn't consulted
 * (Copilot offline / a judge failure). It is an <b>explanation</b> for the reviewer — the deterministic
 * effective-version gate ({@link FixValidator}) is what actually validates the fix — so this NEVER blocks a train.
 */
public record FixDiffVerdict(boolean available, boolean fixesTheVuln, String whatChanged, String reason) {

    public static FixDiffVerdict unavailable(String reason) {
        return new FixDiffVerdict(false, false, "", reason);
    }
}
