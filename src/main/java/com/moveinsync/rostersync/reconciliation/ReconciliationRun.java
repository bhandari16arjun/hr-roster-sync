package com.moveinsync.rostersync.reconciliation;

import java.time.Instant;
import java.util.UUID;

/** Read model of a reconciliation run, as returned by the API. */
public record ReconciliationRun(UUID id, String tenantId, String status, int fileRows, Integer declaredRows,
                                int invalidRows, int activeBefore, int newCount, int changedCount,
                                int unchangedCount, int staleCount, int missingCount, int appliedCount,
                                int deactivatedCount, String brakeReason, Long compareMillis, Instant startedAt,
                                Instant finishedAt, String decidedBy, Instant decidedAt) {

    public static final String RUNNING = "RUNNING";
    public static final String APPLYING = "APPLYING";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String FAILED = "FAILED";
}
