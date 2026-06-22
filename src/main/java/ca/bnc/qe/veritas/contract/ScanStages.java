package ca.bnc.qe.veritas.contract;

import java.util.Map;

/**
 * The named progress stages a contract scan moves through, in order. Stored on {@code Scan.stage}, polled
 * by the dashboard stepper, and logged to the server console at each transition so a run can be followed
 * from the logs too. Single source of truth for the stage names (the React stepper mirrors these).
 */
public final class ScanStages {

    private ScanStages() {}

    public static final String QUEUED = "QUEUED";
    public static final String CLONING = "CLONING";
    public static final String RESOLVING_SPEC = "RESOLVING_SPEC";
    public static final String EXTRACTING = "EXTRACTING";
    public static final String DIFFING = "DIFFING";
    public static final String RECONCILING = "RECONCILING";
    public static final String REPORTING = "REPORTING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            QUEUED, "Queued",
            CLONING, "Cloning the repository from Bitbucket",
            RESOLVING_SPEC, "Locating the OpenAPI spec",
            EXTRACTING, "Reading the API from the code (static analysis)",
            DIFFING, "Comparing the code against the spec",
            RECONCILING, "AI review and corrected spec (Copilot)",
            REPORTING, "Scoring fidelity and building the report",
            DONE, "Completed",
            FAILED, "Failed");

    /** Friendly one-line description of a stage (matches the dashboard wording), for logs/UX. */
    public static String describe(String stage) {
        return DESCRIPTIONS.getOrDefault(stage, stage);
    }
}
