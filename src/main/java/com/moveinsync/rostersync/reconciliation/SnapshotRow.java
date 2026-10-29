package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.domain.HrRecord;

/** One valid row of the nightly export. */
public record SnapshotRow(long rowNumber, String employeeId, long version, HrRecord record) {
}
