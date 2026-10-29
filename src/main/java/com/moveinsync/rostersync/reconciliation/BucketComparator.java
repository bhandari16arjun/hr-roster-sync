package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.employee.EmployeeRepository.StoredDigest;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Compares ONE bucket of the export with the same bucket of the store. Pure logic, no I/O:
 *
 *   1. put the bucket's export rows in a HashMap (id -> row)            O(rows in bucket) memory
 *   2. stream the stored rows of the bucket; for each, remove its export row from the map and
 *      classify it (changed / unchanged / stale / conflict), or mark it MISSING if absent
 *   3. whatever is left in the map is NEW
 *
 * Classification (export row vs stored row):
 *   not stored                                         -> NEW       (apply)
 *   export.version >  stored.version, data differs     -> CHANGED   (apply)
 *   export.version >  stored.version, same data        -> UNCHANGED (apply: version bump only, no event)
 *   export.version == stored.version, same data         -> UNCHANGED (skip)  [or REAPPEARED if stored inactive]
 *   export.version == stored.version, data differs      -> CONFLICT  (skip, reported)
 *   export.version <  stored.version                   -> STALE     (skip: the API already delivered newer data)
 *   stored ACTIVE but not in export                    -> MISSING   (deactivate later, behind the safety brake)
 */
public final class BucketComparator {

    public interface Sink {
        void apply(SnapshotRow row);

        void missing(StoredDigest stored);

        void conflict(SnapshotRow row);
    }

    public static final class Counts {
        public int activeBefore;
        public int newRows;
        public int changed;
        public int unchanged;
        public int stale;
        public int conflicts;
        public int missing;
        public int skippedInvalid;

        public void add(Counts o) {
            skippedInvalid += o.skippedInvalid;
            activeBefore += o.activeBefore;
            newRows += o.newRows;
            changed += o.changed;
            unchanged += o.unchanged;
            stale += o.stale;
            conflicts += o.conflicts;
            missing += o.missing;
        }
    }

    /**
     * @param exportRows  feeds the bucket's export rows (duplicates of one employee: highest version wins)
     * @param storedRows  feeds the bucket's stored rows
     */
    public Counts compare(Consumer<Consumer<SnapshotRow>> exportRows,
                          Consumer<Consumer<StoredDigest>> storedRows,
                          Sink sink) {
        return compare(exportRows, storedRows, id -> false, sink);
    }

    /**
     * @param presentButInvalid employees whose export row was INVALID: they are in the file, so they
     *                          must not be treated as missing (a bad phone number must never
     *                          deactivate someone); their stored record is simply left as it is.
     */
    public Counts compare(Consumer<Consumer<SnapshotRow>> exportRows,
                          Consumer<Consumer<StoredDigest>> storedRows,
                          Predicate<String> presentButInvalid,
                          Sink sink) {
        Map<String, SnapshotRow> pending = new HashMap<>();
        exportRows.accept(row -> pending.merge(row.employeeId(), row,
                (a, b) -> a.version() >= b.version() ? a : b));

        Counts c = new Counts();
        storedRows.accept(stored -> {
            boolean active = stored.status() == EmployeeStatus.ACTIVE;
            if (active) {
                c.activeBefore++;
            }
            SnapshotRow row = pending.remove(stored.employeeId());
            if (row == null) {
                if (presentButInvalid.test(stored.employeeId())) {
                    c.skippedInvalid++;
                    return;
                }
                if (active) {
                    c.missing++;
                    sink.missing(stored);
                }
                return;
            }
            boolean sameData = row.record().hash().equals(stored.hrHash());
            if (row.version() > stored.hrVersion()) {
                if (sameData && active) {
                    c.unchanged++;
                } else {
                    c.changed++;
                }
                sink.apply(row);
            } else if (row.version() == stored.hrVersion()) {
                if (sameData) {
                    if (active) {
                        c.unchanged++;
                    } else {
                        c.changed++;      // reappeared after a reconciliation deactivation
                        sink.apply(row);
                    }
                } else {
                    c.conflicts++;
                    sink.conflict(row);
                }
            } else {
                c.stale++;
            }
        });

        for (SnapshotRow row : pending.values()) {
            c.newRows++;
            sink.apply(row);
        }
        return c;
    }
}
