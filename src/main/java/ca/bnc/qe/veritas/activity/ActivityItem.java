package ca.bnc.qe.veritas.activity;

import java.time.Instant;

/**
 * One row of the unified activity feed. {@code status} is one of the FIVE plain states the whole app
 * shares: QUEUED · RUNNING · WAITING_FOR_YOU · COMPLETED · FAILED. {@code link} is the dashboard route
 * that shows the task; {@code acked} means the user dismissed this item (persisted server-side).
 */
public record ActivityItem(String id, String type, String label, String status, String stage,
                           String detail, boolean needsAttention, Instant startedAt, Instant finishedAt,
                           String link, boolean acked) {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String WAITING_FOR_YOU = "WAITING_FOR_YOU";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    public boolean terminal() {
        return COMPLETED.equals(status) || FAILED.equals(status);
    }
}
