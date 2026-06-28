package ca.bnc.qe.veritas.integration.xray;

/**
 * The latest execution status of one Xray test, read back from Xray (read-only — Xray is the system of record where
 * the generated tests actually run). {@code status} is the raw Xray/Jira status string (e.g. PASS, FAIL, BLOCKED,
 * TODO, EXECUTING, or a workflow status); downstream code normalises it into outcome buckets.
 *
 * @param testKey the Xray Test issue key (e.g. CIAM-101)
 * @param status  the raw status string as Xray returned it
 */
public record XrayRunStatus(String testKey, String status) {}
