package ca.bnc.qe.veritas.snyk.fix;

import java.util.List;

/** The lifecycle states of a fix train and its steps (stored as strings so SQLite can ALTER-add freely). */
public final class SnykFixStatus {

    // Train status
    public static final String PLANNING = "PLANNING";
    public static final String AWAITING_CONFIRM = "AWAITING_CONFIRM"; // plan computed → waiting for the user to confirm
    public static final String CHECKING = "CHECKING";                 // breaking-change LLM
    public static final String VERIFYING = "VERIFYING";               // local reactor build
    public static final String OPENING_PRS = "OPENING_PRS";
    public static final String PR_OPEN = "PR_OPEN";                    // clean → train opened
    public static final String AWAITING_MANUAL_FIX = "AWAITING_MANUAL_FIX";  // breaking → branch pushed, PR held
    public static final String DONE = "DONE";                         // all PRs merged
    public static final String FAILED = "FAILED";

    // Step status
    public static final String PLANNED = "PLANNED";
    public static final String BRANCH_PUSHED = "BRANCH_PUSHED";
    public static final String STEP_PR_OPEN = "PR_OPEN";
    public static final String MERGED = "MERGED";
    public static final String MANUAL = "MANUAL";
    public static final String STEP_FAILED = "FAILED";

    // Who opened a step's PR
    public static final String BY_VERITAS = "VERITAS";
    public static final String BY_USER = "USER";

    /**
     * The non-terminal train states — a train in any of these is still "in flight": neither DONE nor FAILED.
     * Used both by the submit dedup guard (don't start a second train while one is live) and by the managerial
     * summary's "in progress" bucket. The single source of truth for "not finished".
     */
    public static final List<String> NON_TERMINAL = List.of(
            PLANNING, AWAITING_CONFIRM, CHECKING, VERIFYING, OPENING_PRS, PR_OPEN, AWAITING_MANUAL_FIX);

    /**
     * The machine-driven states the reconciler may safely restart-recover or time out — never the human-wait
     * states (AWAITING_CONFIRM / AWAITING_MANUAL_FIX / PR_OPEN), which are meant to sit until a person acts.
     */
    public static final List<String> MACHINE_DRIVEN = List.of(PLANNING, CHECKING, VERIFYING, OPENING_PRS);

    private SnykFixStatus() {
    }
}
