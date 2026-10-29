package com.moveinsync.rostersync.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * THE core rule engine. Pure function: (stored state, incoming change) -> decision.
 * No database, no Spring, so every rule is unit-tested directly.
 *
 * Version rule (per employee):
 *   incoming.version <  stored.version  -> STALE      (ignored)
 *   incoming.version == stored.version  -> DUPLICATE  if the content is identical (replay, no-op)
 *                                       -> CONFLICT   if the content differs (reported, not applied)
 *   incoming.version >  stored.version  -> APPLY      (or VERSION_ONLY if nothing actually changed)
 *
 * Ownership rule:
 *   HR changes only ever replace the HR-owned fields. The transport-owned fields are copied
 *   from the stored state unchanged, so an HR update can never overwrite a pickup point.
 */
public final class EmployeeMerger {

    public MergeDecision merge(Optional<EmployeeState> stored, HrChange change) {
        if (stored.isEmpty()) {
            return mergeIntoNothing(change);
        }
        EmployeeState current = stored.get();
        long incoming = change.version();
        long have = current.hrVersion();

        if (incoming < have) {
            return new MergeDecision.Stale(have);
        }
        if (incoming == have) {
            if (isSnapshotReappearance(current, change)) {
                // Employee was deactivated by reconciliation (no version bump) and is present again in a
                // later export with the same data: the export is authoritative that they still exist.
                EmployeeState next = current.withHr(EmployeeStatus.ACTIVE, have, current.hrRecord());
                return new MergeDecision.Apply(next, ChangeEventType.EMPLOYEE_REACTIVATED, List.of("status"), false);
            }
            return sameContent(current, change)
                    ? new MergeDecision.Duplicate()
                    : new MergeDecision.Conflict("SAME_VERSION_DIFFERENT_CONTENT");
        }
        // incoming > have
        return change.kind() == HrChange.Kind.UPSERT
                ? applyUpsert(current, change)
                : applyDeactivate(current, change);
    }

    /**
     * Deactivation decided by the nightly reconciliation. It has no source version of its own, so it
     * is only applied if the employee has not changed since the export was compared
     * (expectedVersion = version seen at compare time). A newer API update always wins.
     */
    public MergeDecision mergeReconciliationDeactivate(Optional<EmployeeState> stored, long expectedVersion) {
        if (stored.isEmpty()) {
            return new MergeDecision.Stale(-1);
        }
        EmployeeState current = stored.get();
        if (current.hrVersion() != expectedVersion) {
            return new MergeDecision.Stale(current.hrVersion());
        }
        if (!current.isActive()) {
            return new MergeDecision.Duplicate();
        }
        EmployeeState next = current.withHr(EmployeeStatus.INACTIVE, current.hrVersion(), current.hrRecord());
        return new MergeDecision.Apply(next, ChangeEventType.EMPLOYEE_DEACTIVATED, List.of("status"), false);
    }

    private MergeDecision mergeIntoNothing(HrChange change) {
        if (change.kind() == HrChange.Kind.UPSERT) {
            EmployeeState created = new EmployeeState(change.tenantId(), change.employeeId(),
                    EmployeeStatus.ACTIVE, change.version(), change.record(), TransportFields.EMPTY);
            return new MergeDecision.Apply(created, ChangeEventType.EMPLOYEE_CREATED,
                    change.record().changedFields(null), true);
        }
        // DEACTIVATE for someone we have never seen: store a tombstone so that an older CREATE
        // arriving later is recognised as stale and does not bring them back.
        EmployeeState tombstone = new EmployeeState(change.tenantId(), change.employeeId(),
                EmployeeStatus.INACTIVE, change.version(), null, TransportFields.EMPTY);
        return new MergeDecision.Apply(tombstone, ChangeEventType.EMPLOYEE_DEACTIVATED, List.of("status"), true);
    }

    private MergeDecision applyUpsert(EmployeeState current, HrChange change) {
        List<String> changed = new ArrayList<>(change.record().changedFields(current.hrRecord()));
        boolean reactivating = !current.isActive();
        if (reactivating) {
            changed.add(0, "status");
        }
        EmployeeState next = current.withHr(EmployeeStatus.ACTIVE, change.version(), change.record());
        if (changed.isEmpty()) {
            return new MergeDecision.Apply(next, null, List.of(), false); // version bump only
        }
        ChangeEventType type = reactivating ? ChangeEventType.EMPLOYEE_REACTIVATED : ChangeEventType.EMPLOYEE_UPDATED;
        return new MergeDecision.Apply(next, type, List.copyOf(changed), false);
    }

    private MergeDecision applyDeactivate(EmployeeState current, HrChange change) {
        // keep the last known HR record for audit; only status and version change
        EmployeeState next = current.withHr(EmployeeStatus.INACTIVE, change.version(), current.hrRecord());
        if (!current.isActive()) {
            return new MergeDecision.Apply(next, null, List.of(), false); // already inactive
        }
        return new MergeDecision.Apply(next, ChangeEventType.EMPLOYEE_DEACTIVATED, List.of("status"), false);
    }

    private boolean isSnapshotReappearance(EmployeeState current, HrChange change) {
        return change.source() == ChangeSource.SNAPSHOT
                && change.kind() == HrChange.Kind.UPSERT
                && !current.isActive()
                && current.hrRecord() != null
                && change.record().hash().equals(current.hrHash());
    }

    private boolean sameContent(EmployeeState current, HrChange change) {
        if (change.kind() == HrChange.Kind.DEACTIVATE) {
            return !current.isActive();
        }
        // Same HR data at the same version is a replay even if the employee was since deactivated by
        // reconciliation (which does not bump the version): a replay must never resurrect anyone.
        return current.hrRecord() != null && change.record().hash().equals(current.hrHash());
    }
}
