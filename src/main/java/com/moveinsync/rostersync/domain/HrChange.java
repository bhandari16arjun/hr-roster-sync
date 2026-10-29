package com.moveinsync.rostersync.domain;

import java.util.Objects;

/**
 * One change coming from HR, either from the real-time API or from a row of the nightly export.
 *
 * HR sends the FULL HR record with every create/update (state transfer, not a delta). That is what
 * makes out-of-order delivery easy: the highest version is simply the truth.
 *
 * Idempotency key = (tenantId, employeeId, version).
 */
public record HrChange(Kind kind, String tenantId, String employeeId, long version,
                       HrRecord record, ChangeSource source) {

    public enum Kind {
        /** CREATE and UPDATE are treated identically: "this is the employee's state at version N". */
        UPSERT,
        DEACTIVATE
    }

    public HrChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(source, "source");
        if (kind == Kind.UPSERT) {
            Objects.requireNonNull(record, "record is required for UPSERT");
        }
    }

    public static HrChange upsert(String tenantId, String employeeId, long version, HrRecord record, ChangeSource source) {
        return new HrChange(Kind.UPSERT, tenantId, employeeId, version, record, source);
    }

    public static HrChange deactivate(String tenantId, String employeeId, long version, ChangeSource source) {
        return new HrChange(Kind.DEACTIVATE, tenantId, employeeId, version, null, source);
    }

    public String idempotencyKey() {
        return tenantId + ":" + employeeId + ":" + version;
    }
}
