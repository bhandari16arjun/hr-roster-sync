package com.moveinsync.rostersync.domain;

import java.util.Objects;

/**
 * Immutable snapshot of one employee as stored by MoveInSync.
 *
 * @param hrRecord may be null for a "tombstone": an employee we only ever saw a DEACTIVATE for.
 *                 Keeping the tombstone (with its version) stops a late, older CREATE from
 *                 resurrecting someone who has already left.
 */
public record EmployeeState(String tenantId,
                            String employeeId,
                            EmployeeStatus status,
                            long hrVersion,
                            HrRecord hrRecord,
                            TransportFields transport) {

    public EmployeeState {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(status, "status");
        transport = transport == null ? TransportFields.EMPTY : transport;
    }

    public boolean isActive() {
        return status == EmployeeStatus.ACTIVE;
    }

    /** Hash of the HR-owned data; an empty record is used for tombstones. */
    public String hrHash() {
        return (hrRecord == null ? new HrRecord(null, null, null, null, null, null) : hrRecord).hash();
    }

    public int bucket() {
        return Buckets.of(employeeId);
    }

    public EmployeeState withHr(EmployeeStatus newStatus, long newVersion, HrRecord newRecord) {
        return new EmployeeState(tenantId, employeeId, newStatus, newVersion, newRecord, transport);
    }

    public EmployeeState withTransport(TransportFields newTransport) {
        return new EmployeeState(tenantId, employeeId, status, hrVersion, hrRecord, newTransport);
    }
}
