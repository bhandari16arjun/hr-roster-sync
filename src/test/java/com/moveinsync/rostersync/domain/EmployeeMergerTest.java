package com.moveinsync.rostersync.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Every hard rule of ordering, idempotency and ownership, tested without Spring or a database. */
class EmployeeMergerTest {

    private static final String T = "acme";
    private final EmployeeMerger merger = new EmployeeMerger();

    private static HrRecord rec(String office, String shift) {
        return new HrRecord("Asha Rao", "asha@acme.com", "+919800000001", "M1", office, shift);
    }

    private static EmployeeState stored(long version, HrRecord r) {
        return new EmployeeState(T, "E1", EmployeeStatus.ACTIVE, version, r, new TransportFields("Gate 3", "wheelchair"));
    }

    @Test
    void createsUnknownEmployee() {
        MergeDecision d = merger.merge(Optional.empty(), HrChange.upsert(T, "E1", 1, rec("BLR-01", "S1"), ChangeSource.API));
        assertThat(d).isInstanceOf(MergeDecision.Apply.class);
        MergeDecision.Apply a = (MergeDecision.Apply) d;
        assertThat(a.eventType()).isEqualTo(ChangeEventType.EMPLOYEE_CREATED);
        assertThat(a.isInsert()).isTrue();
        assertThat(a.newState().hrVersion()).isEqualTo(1);
    }

    @Test
    void newerVersionIsApplied() {
        MergeDecision d = merger.merge(Optional.of(stored(6, rec("BLR-01", "S1"))),
                HrChange.upsert(T, "E1", 7, rec("BLR-02", "S1"), ChangeSource.API));
        MergeDecision.Apply a = (MergeDecision.Apply) d;
        assertThat(a.eventType()).isEqualTo(ChangeEventType.EMPLOYEE_UPDATED);
        assertThat(a.changedFields()).containsExactly("officeCode");
        assertThat(a.newState().hrRecord().officeCode()).isEqualTo("BLR-02");
    }

    @Test
    void olderVersionIsStale() {
        MergeDecision d = merger.merge(Optional.of(stored(7, rec("BLR-02", "S7"))),
                HrChange.upsert(T, "E1", 6, rec("BLR-01", "S6"), ChangeSource.API));
        assertThat(d).isInstanceOf(MergeDecision.Stale.class);
    }

    @Test
    void sameVersionSameContentIsDuplicate() {
        MergeDecision d = merger.merge(Optional.of(stored(5, rec("BLR-01", "S1"))),
                HrChange.upsert(T, "E1", 5, rec("BLR-01", "S1"), ChangeSource.API));
        assertThat(d).isInstanceOf(MergeDecision.Duplicate.class);
    }

    @Test
    void normalisationMakesCosmeticDifferencesADuplicate() {
        HrRecord messy = new HrRecord("  Asha Rao ", "ASHA@ACME.COM", "+919800000001", "M1", "blr-01", "S1");
        MergeDecision d = merger.merge(Optional.of(stored(5, rec("BLR-01", "S1"))),
                HrChange.upsert(T, "E1", 5, messy, ChangeSource.API));
        assertThat(d).isInstanceOf(MergeDecision.Duplicate.class);
    }

    @Test
    void sameVersionDifferentContentIsConflict() {
        MergeDecision d = merger.merge(Optional.of(stored(5, rec("BLR-01", "S1"))),
                HrChange.upsert(T, "E1", 5, rec("HYD-01", "S1"), ChangeSource.API));
        assertThat(d).isInstanceOf(MergeDecision.Conflict.class);
    }

    @Test
    void hrUpdateNeverTouchesTransportFields() {
        MergeDecision.Apply a = (MergeDecision.Apply) merger.merge(Optional.of(stored(1, rec("BLR-01", "S1"))),
                HrChange.upsert(T, "E1", 2, rec("BLR-02", "S2"), ChangeSource.API));
        assertThat(a.newState().transport()).isEqualTo(new TransportFields("Gate 3", "wheelchair"));
    }

    @Test
    void newerVersionWithSameDataIsVersionOnlyWithoutEvent() {
        MergeDecision.Apply a = (MergeDecision.Apply) merger.merge(Optional.of(stored(3, rec("BLR-01", "S1"))),
                HrChange.upsert(T, "E1", 5, rec("BLR-01", "S1"), ChangeSource.SNAPSHOT));
        assertThat(a.outcome()).isEqualTo(MergeDecision.Outcome.VERSION_ONLY);
        assertThat(a.eventType()).isNull();
        assertThat(a.newState().hrVersion()).isEqualTo(5);
    }

    @Test
    void deactivateThenReactivateWithHigherVersion() {
        EmployeeState s = stored(3, rec("BLR-01", "S1"));
        MergeDecision.Apply off = (MergeDecision.Apply) merger.merge(Optional.of(s),
                HrChange.deactivate(T, "E1", 4, ChangeSource.API));
        assertThat(off.eventType()).isEqualTo(ChangeEventType.EMPLOYEE_DEACTIVATED);
        assertThat(off.newState().isActive()).isFalse();
        assertThat(off.newState().hrRecord()).isNotNull(); // kept for audit

        MergeDecision.Apply on = (MergeDecision.Apply) merger.merge(Optional.of(off.newState()),
                HrChange.upsert(T, "E1", 5, rec("BLR-01", "S1"), ChangeSource.API));
        assertThat(on.eventType()).isEqualTo(ChangeEventType.EMPLOYEE_REACTIVATED);
        assertThat(on.newState().isActive()).isTrue();
    }

    @Test
    void deactivateOfUnknownEmployeeLeavesTombstoneThatBlocksOlderCreate() {
        MergeDecision.Apply tomb = (MergeDecision.Apply) merger.merge(Optional.empty(),
                HrChange.deactivate(T, "E9", 10, ChangeSource.API));
        assertThat(tomb.newState().isActive()).isFalse();
        assertThat(tomb.newState().hrVersion()).isEqualTo(10);

        MergeDecision lateCreate = merger.merge(Optional.of(tomb.newState()),
                HrChange.upsert(T, "E9", 9, rec("BLR-01", "S1"), ChangeSource.API));
        assertThat(lateCreate).isInstanceOf(MergeDecision.Stale.class);
    }

    @Test
    void duplicateDeactivateIsNoOp() {
        EmployeeState inactive = stored(4, rec("BLR-01", "S1")).withHr(EmployeeStatus.INACTIVE, 4, rec("BLR-01", "S1"));
        assertThat(merger.merge(Optional.of(inactive), HrChange.deactivate(T, "E1", 4, ChangeSource.API)))
                .isInstanceOf(MergeDecision.Duplicate.class);
    }

    @Test
    void reconciliationDeactivateOnlyIfVersionUnchanged() {
        EmployeeState s = stored(4, rec("BLR-01", "S1"));
        assertThat(merger.mergeReconciliationDeactivate(Optional.of(s), 4)).isInstanceOf(MergeDecision.Apply.class);
        // an API update moved the employee to v5 after the export was compared: do not deactivate
        assertThat(merger.mergeReconciliationDeactivate(Optional.of(s), 3)).isInstanceOf(MergeDecision.Stale.class);
    }

    @Test
    void apiReplayDoesNotResurrectReconciliationDeactivatedEmployee() {
        EmployeeState inactive = stored(4, rec("BLR-01", "S1")).withHr(EmployeeStatus.INACTIVE, 4, rec("BLR-01", "S1"));
        assertThat(merger.merge(Optional.of(inactive), HrChange.upsert(T, "E1", 4, rec("BLR-01", "S1"), ChangeSource.API)))
                .isInstanceOf(MergeDecision.Duplicate.class);
    }

    @Test
    void snapshotReappearanceReactivatesReconciliationDeactivatedEmployee() {
        EmployeeState inactive = stored(4, rec("BLR-01", "S1")).withHr(EmployeeStatus.INACTIVE, 4, rec("BLR-01", "S1"));
        MergeDecision d = merger.merge(Optional.of(inactive),
                HrChange.upsert(T, "E1", 4, rec("BLR-01", "S1"), ChangeSource.SNAPSHOT));
        assertThat(((MergeDecision.Apply) d).eventType()).isEqualTo(ChangeEventType.EMPLOYEE_REACTIVATED);
    }

    @Test
    void hrRecordToStringNeverPrintsPersonalData() {
        assertThat(rec("BLR-01", "S1").toString()).doesNotContain("Asha", "asha@acme.com", "+9198");
    }
}
