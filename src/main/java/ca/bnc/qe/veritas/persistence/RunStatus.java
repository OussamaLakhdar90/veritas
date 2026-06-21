package ca.bnc.qe.veritas.persistence;

public enum RunStatus {
    QUEUED,
    RUNNING,
    AWAITING_GATE,
    COMPLETED,
    FAILED
}
