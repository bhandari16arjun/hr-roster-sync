package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.HrRecord;
import com.moveinsync.rostersync.employee.EmployeeRepository.StoredDigest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BucketComparatorTest {

    private static HrRecord rec(String office) {
        return new HrRecord("N", "n@x.com", null, null, office, "S1");
    }

    private static SnapshotRow row(String id, long v, String office) {
        return new SnapshotRow(1, id, v, rec(office));
    }

    private static StoredDigest stored(String id, long v, String office, EmployeeStatus status) {
        return new StoredDigest(id, status, v, rec(office).hash());
    }

    @Test
    void classifiesEveryCase() {
        List<SnapshotRow> export = List.of(
                row("SAME", 1, "A"),          // unchanged
                row("CHG", 2, "B"),           // changed (v2 > v1, data differs)
                row("BUMP", 3, "A"),          // version bump only
                row("OLD", 1, "Z"),           // stale (stored v5)
                row("CONF", 4, "Z"),          // same version, different data
                row("NEW", 1, "A"),           // not stored
                row("BACK", 2, "A"));         // stored inactive at same version -> reappeared
        List<StoredDigest> store = List.of(
                stored("SAME", 1, "A", EmployeeStatus.ACTIVE),
                stored("CHG", 1, "A", EmployeeStatus.ACTIVE),
                stored("BUMP", 1, "A", EmployeeStatus.ACTIVE),
                stored("OLD", 5, "A", EmployeeStatus.ACTIVE),
                stored("CONF", 4, "A", EmployeeStatus.ACTIVE),
                stored("GONE", 2, "A", EmployeeStatus.ACTIVE),      // missing
                stored("LEFT", 2, "A", EmployeeStatus.INACTIVE),    // already inactive: not missing
                stored("BACK", 2, "A", EmployeeStatus.INACTIVE));

        List<String> applied = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        BucketComparator.Counts c = new BucketComparator().compare(
                sink -> export.forEach(sink), sink -> store.forEach(sink), new BucketComparator.Sink() {
                    public void apply(SnapshotRow r) { applied.add(r.employeeId()); }
                    public void missing(StoredDigest s) { missing.add(s.employeeId()); }
                    public void conflict(SnapshotRow r) { conflicts.add(r.employeeId()); }
                });

        assertThat(applied).containsExactlyInAnyOrder("CHG", "BUMP", "NEW", "BACK");
        assertThat(missing).containsExactly("GONE");
        assertThat(conflicts).containsExactly("CONF");
        assertThat(c.activeBefore).isEqualTo(6);
        assertThat(c.newRows).isEqualTo(1);
        assertThat(c.changed).isEqualTo(2);
        assertThat(c.unchanged).isEqualTo(2);
        assertThat(c.stale).isEqualTo(1);
        assertThat(c.missing).isEqualTo(1);
    }

    @Test
    void invalidRowIsNotTreatedAsMissing() {
        List<String> missing = new ArrayList<>();
        BucketComparator.Counts c = new BucketComparator().compare(sink -> { },
                sink -> sink.accept(stored("E1", 1, "A", EmployeeStatus.ACTIVE)),
                "E1"::equals,
                new BucketComparator.Sink() {
                    public void apply(SnapshotRow r) { }
                    public void missing(StoredDigest s) { missing.add(s.employeeId()); }
                    public void conflict(SnapshotRow r) { }
                });
        assertThat(missing).isEmpty();
        assertThat(c.skippedInvalid).isEqualTo(1);
    }

    @Test
    void duplicateRowsInExportKeepHighestVersion() {
        List<SnapshotRow> applied = new ArrayList<>();
        new BucketComparator().compare(sink -> {
            sink.accept(row("E1", 3, "B"));
            sink.accept(row("E1", 2, "A"));
        }, sink -> { }, new BucketComparator.Sink() {
            public void apply(SnapshotRow r) { applied.add(r); }
            public void missing(StoredDigest s) { }
            public void conflict(SnapshotRow r) { }
        });
        assertThat(applied).singleElement().satisfies(r -> assertThat(r.version()).isEqualTo(3));
    }
}
