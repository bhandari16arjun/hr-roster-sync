package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.booking.BookingRepository;
import com.moveinsync.rostersync.common.error.ConflictException;
import com.moveinsync.rostersync.outbox.OutboxRelay;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.SnapshotCsv;
import com.moveinsync.rostersync.sync.EmployeeSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationIntegrationTest extends IntegrationTest {

    @Autowired
    ReconciliationService service;
    @Autowired
    ReconciliationRepository runs;
    @Autowired
    EmployeeSyncService sync;
    @Autowired
    BookingRepository bookings;
    @Autowired
    OutboxRelay relay;

    private static SnapshotCsv export(int from, int toExclusive, long version) {
        SnapshotCsv csv = new SnapshotCsv();
        for (int i = from; i < toExclusive; i++) {
            csv.row("E" + i, version, "BLR-01", "S1");
        }
        return csv;
    }

    private ReconciliationRun load2000() {
        return service.reconcile(ACME, export(0, 2000, 1).stream());
    }

    @Test
    void firstExportCreatesEveryone() {
        ReconciliationRun run = load2000();
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.newCount()).isEqualTo(2000);
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'ACTIVE'")).isEqualTo(2000);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'EMPLOYEE_CREATED'")).isEqualTo(2000);
    }

    @Test
    void reRunningTheSameExportChangesNothing() {
        load2000();
        int events = count("SELECT count(*) FROM outbox_event");
        ReconciliationRun again = load2000();
        assertThat(again.status()).isEqualTo("COMPLETED");
        assertThat(again.unchangedCount()).isEqualTo(2000);
        assertThat(again.appliedCount()).isZero();
        assertThat(again.deactivatedCount()).isZero();
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(events);
    }

    @Test
    void normalNightAppliesChangesAndDeactivatesLeavers() {
        load2000();
        bookings.create(ACME, "E1999", LocalDate.now().plusDays(1), "2B");
        // E1990..E1999 left (10 = 0.5%), E0..E9 changed office at v2, E2000..E2004 joined
        SnapshotCsv csv = new SnapshotCsv();
        for (int i = 0; i < 1990; i++) {
            csv.row("E" + i, i < 10 ? 2 : 1, i < 10 ? "HYD-01" : "BLR-01", "S1");
        }
        for (int i = 2000; i < 2005; i++) {
            csv.row("E" + i, 1, "BLR-02", "S1");
        }
        ReconciliationRun run = service.reconcile(ACME, csv.stream());

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.changedCount()).isEqualTo(10);
        assertThat(run.newCount()).isEqualTo(5);
        assertThat(run.missingCount()).isEqualTo(10);
        assertThat(run.deactivatedCount()).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'ACTIVE'")).isEqualTo(1995);
        assertThat(count("SELECT count(*) FROM employee WHERE office_code = 'HYD-01'")).isEqualTo(10);

        relay.drain(); // deactivation side effects happen through the outbox
        assertThat(bookings.findForEmployee(ACME, "E1999")).singleElement()
                .satisfies(b -> assertThat(b.status()).isEqualTo("CANCELLED"));
    }

    /** The case study example: the nightly file is cut off (here 1,200 of 2,000 rows). */
    @Test
    void truncatedExportTripsTheSafetyBrakeAndDeactivatesNobody() {
        load2000();
        ReconciliationRun run = service.reconcile(ACME, export(0, 1200, 1).stream());

        assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.missingCount()).isEqualTo(800);
        assertThat(run.activeBefore()).isEqualTo(2000);
        assertThat(run.brakeReason()).contains("800 of 2000").contains("40.0%");
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'ACTIVE'")).isEqualTo(2000);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'EMPLOYEE_DEACTIVATED'")).isZero();
        assertThat(runs.missingSample(run.id(), 5)).hasSize(5);
    }

    @Test
    void brakedChangesAreNotAppliedEither() {
        load2000();
        SnapshotCsv csv = export(0, 1200, 1);
        csv.row("E5000", 1, "BLR-01", "S1"); // a joiner inside the broken file
        ReconciliationRun run = service.reconcile(ACME, csv.stream());
        assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(count("SELECT count(*) FROM employee WHERE employee_id = 'E5000'")).isZero();
    }

    @Test
    void operatorApprovalAppliesTheBrakedRun() {
        load2000();
        ReconciliationRun braked = service.reconcile(ACME, export(0, 1200, 1).stream());
        ReconciliationRun approved = service.approve(ACME, braked.id(), "acme-ops");
        assertThat(approved.status()).isEqualTo("COMPLETED");
        assertThat(approved.decidedBy()).isEqualTo("acme-ops");
        assertThat(approved.deactivatedCount()).isEqualTo(800);
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'ACTIVE'")).isEqualTo(1200);
        // a run can be decided only once
        assertThatThrownBy(() -> service.approve(ACME, braked.id(), "acme-ops")).isInstanceOf(ConflictException.class);
    }

    @Test
    void operatorRejectionLeavesEverythingAsIs() {
        load2000();
        ReconciliationRun braked = service.reconcile(ACME, export(0, 1200, 1).stream());
        assertThat(service.reject(ACME, braked.id(), "acme-ops").status()).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'ACTIVE'")).isEqualTo(2000);
    }

    @Test
    void newerExportSupersedesARunAwaitingApproval() {
        load2000();
        ReconciliationRun braked = service.reconcile(ACME, export(0, 1200, 1).stream());
        load2000();
        assertThat(service.get(ACME, braked.id()).status()).isEqualTo("SUPERSEDED");
        assertThatThrownBy(() -> service.approve(ACME, braked.id(), "acme-ops")).isInstanceOf(ConflictException.class);
    }

    @Test
    void trailerMismatchTripsTheBrake() {
        ReconciliationRun run = service.reconcile(ACME, export(0, 100, 1).trailer(150).stream());
        assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.brakeReason()).contains("declares 150 rows but 100");
    }

    @Test
    void invalidRowsAreReportedAndNeverDeactivateAnyone() {
        load2000();
        SnapshotCsv csv = new SnapshotCsv();
        for (int i = 0; i < 2000; i++) {
            if (i == 5) {
                csv.raw("E5,2,Person E5,e5@acme.com,98-BAD-PHONE,M1,BLR-01,S1"); // bad phone
            } else if (i == 6) {
                csv.raw("E6,2,Person E6,e6@acme.com,+919800000006,M1,NOPE-99,S1"); // unknown office
            } else {
                csv.row("E" + i, 1, "BLR-01", "S1");
            }
        }
        csv.raw("E7,1,cut"); // a cut-off row with too few columns
        ReconciliationRun run = service.reconcile(ACME, csv.stream());

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.invalidRows()).isEqualTo(3);
        assertThat(run.deactivatedCount()).isZero();
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'ACTIVE'")).isEqualTo(2000);

        List<Map<String, Object>> errors = runs.errors(run.id(), 100);
        assertThat(errors).extracting(e -> e.get("code"))
                .containsExactlyInAnyOrder("INVALID_PHONE", "UNKNOWN_OFFICE", "INCOMPLETE_ROW");
        assertThat(errors).extracting(e -> e.get("employee_id")).contains("E5", "E6");
        // the error report holds codes only, never the bad values
        assertThat(errors.toString()).doesNotContain("98-BAD-PHONE", "NOPE-99", "e5@acme.com");
    }

    @Test
    void staleExportRowNeverOverwritesNewerApiUpdate() {
        load2000();
        sync.applyOne(upsert("E1", 5, "HYD-01", "NIGHT")); // real-time update after the export was taken
        ReconciliationRun run = load2000();                   // export still says v1 / BLR-01
        assertThat(run.staleCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT office_code FROM employee WHERE employee_id = 'E1'", String.class))
                .isEqualTo("HYD-01");
    }

    @Test
    void onlyOneRunPerTenantAtATime() {
        jdbc.update("INSERT INTO reconciliation_run (id, tenant_id, status) VALUES (gen_random_uuid(), 'acme', 'RUNNING')");
        assertThatThrownBy(() -> load2000()).isInstanceOf(ConflictException.class);
        // another tenant is not blocked
        assertThat(service.reconcile(GLOBEX, new SnapshotCsv().row("G1", 1, "PUN-01", "S1").stream()).status())
                .isEqualTo("COMPLETED");
    }

    @Test
    void interruptedRunsAreFailedOnStartup() {
        jdbc.update("INSERT INTO reconciliation_run (id, tenant_id, status) VALUES (gen_random_uuid(), 'acme', 'APPLYING')");
        service.recoverInterruptedRuns();
        assertThat(count("SELECT count(*) FROM reconciliation_run WHERE status = 'FAILED'")).isEqualTo(1);
    }
}
