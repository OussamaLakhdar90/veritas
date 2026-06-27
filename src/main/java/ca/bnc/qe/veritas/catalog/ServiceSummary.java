package ca.bnc.qe.veritas.catalog;

/**
 * One row of the service catalog: a service the platform holds work for, with how much exists at each pipeline stage.
 * Drives the dashboard's service picker and per-service pipeline-status panel.
 */
public record ServiceSummary(String name, long strategies, long conditions, long cases, long plans, long scans,
                             long codegenRuns) {}
