package com.moveinsync.rostersync.sync;

import com.moveinsync.rostersync.domain.ChangeSource;
import com.moveinsync.rostersync.domain.EmployeeState;
import com.moveinsync.rostersync.domain.HrChange;
import com.moveinsync.rostersync.domain.TransportFields;
import com.moveinsync.rostersync.employee.EmployeeRepository;
import com.moveinsync.rostersync.employee.TransportService;
import com.moveinsync.rostersync.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeSyncIntegrationTest extends IntegrationTest {

    @Autowired
    EmployeeSyncService sync;
    @Autowired
    EmployeeRepository employees;
    @Autowired
    TransportService transport;

    /** The example from the case study: v7 (office transfer) arrives before v6 (shift change). */
    @Test
    void outOfOrderUpdateIsIgnoredAsStale() {
        sync.applyOne(upsert("E1", 5, "BLR-01", "MORNING"));
        // v7 = transfer to BLR-02; HR sends full state, so v7 already carries the shift as of v7
        ApplyResult r7 = sync.applyOne(upsert("E1", 7, "BLR-02", "EVENING"));
        // v6 = the (older) shift change, arriving late
        ApplyResult r6 = sync.applyOne(upsert("E1", 6, "BLR-01", "NIGHT"));

        assertThat(r7.outcome()).isEqualTo("APPLIED");
        assertThat(r6.outcome()).isEqualTo("STALE");
        EmployeeState e = employees.find(ACME, "E1").orElseThrow();
        assertThat(e.hrVersion()).isEqualTo(7);
        assertThat(e.hrRecord().officeCode()).isEqualTo("BLR-02");
        assertThat(e.hrRecord().shiftCode()).isEqualTo("EVENING");
        // one event for create + one for v7, none for the stale v6
        assertThat(count("SELECT count(*) FROM outbox_event WHERE employee_id = 'E1'")).isEqualTo(2);
    }

    /** Replaying the same feed twice changes nothing: same rows, same versions, no new events. */
    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        List<HrChange> feed = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            feed.add(upsert("E" + i, 1, "BLR-01", "S1"));
            feed.add(upsert("E" + i, 2, i % 2 == 0 ? "BLR-02" : "BLR-01", "S2"));
        }
        feed.add(HrChange.deactivate(ACME, "E7", 3, ChangeSource.API));

        sync.apply(ACME, feed);
        String before = jdbc.queryForObject(
                "SELECT string_agg(employee_id || ':' || hr_version || ':' || status || ':' || hr_hash, ',' "
                        + "ORDER BY employee_id) FROM employee", String.class);
        int eventsBefore = count("SELECT count(*) FROM outbox_event");

        List<ApplyResult> replay = sync.apply(ACME, feed);

        String after = jdbc.queryForObject(
                "SELECT string_agg(employee_id || ':' || hr_version || ':' || status || ':' || hr_hash, ',' "
                        + "ORDER BY employee_id) FROM employee", String.class);
        assertThat(after).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(eventsBefore);
        assertThat(replay).allSatisfy(r -> assertThat(r.outcome()).isIn("DUPLICATE", "STALE"));
    }

    @Test
    void hrUpdateNeverOverwritesTransportOwnedFields() {
        sync.applyOne(upsert("E1", 1, "BLR-01", "S1"));
        transport.update(ACME, "E1", new TransportFields("Gate 3, Tower B", "wheelchair"), "acme-transport");

        sync.applyOne(upsert("E1", 2, "HYD-01", "S2"));

        EmployeeState e = employees.find(ACME, "E1").orElseThrow();
        assertThat(e.hrRecord().officeCode()).isEqualTo("HYD-01");
        assertThat(e.transport().pickupPoint()).isEqualTo("Gate 3, Tower B");
        assertThat(e.transport().specialAssistance()).isEqualTo("wheelchair");
    }

    @Test
    void writesAreTenantScoped() {
        sync.applyOne(upsert("E1", 1, "BLR-01", "S1"));
        assertThat(employees.find(GLOBEX, "E1")).isEmpty();
        // the same employee id in another tenant is a different employee
        sync.applyOne(HrChange.upsert(GLOBEX, "E1", 1, rec("PUN-01", "S1"), ChangeSource.API));
        assertThat(employees.find(GLOBEX, "E1").orElseThrow().hrRecord().officeCode()).isEqualTo("PUN-01");
        assertThat(employees.find(ACME, "E1").orElseThrow().hrRecord().officeCode()).isEqualTo("BLR-01");
    }

    /**
     * Concurrency: 8 threads deliver versions 1..200 of the same employee in random order, each
     * version delivered twice. Whatever the interleaving, the final state must be version 200, and
     * every version must have been applied at most once.
     */
    @Test
    void concurrentOutOfOrderDeliveryConvergesToHighestVersion() throws Exception {
        List<HrChange> deliveries = new ArrayList<>();
        for (int v = 1; v <= 200; v++) {
            HrChange c = upsert("HOT", v, v % 2 == 0 ? "BLR-01" : "BLR-02", "S" + v);
            deliveries.add(c);
            deliveries.add(c); // duplicate delivery
        }
        Collections.shuffle(deliveries, new Random(42));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApplyResult>> futures = new ArrayList<>();
        for (HrChange c : deliveries) {
            futures.add(pool.submit(() -> {
                start.await();
                return sync.applyOne(c);
            }));
        }
        start.countDown();
        List<ApplyResult> results = new ArrayList<>();
        for (Future<ApplyResult> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        EmployeeState e = employees.find(ACME, "HOT").orElseThrow();
        assertThat(e.hrVersion()).isEqualTo(200);
        assertThat(e.hrRecord().shiftCode()).isEqualTo("S200");
        assertThat(results).noneMatch(r -> r.outcome().equals("FAILED") || r.outcome().equals("CONFLICT"));
        // no version produced more than one event
        assertThat(count("SELECT count(*) FROM (SELECT hr_version FROM outbox_event WHERE employee_id = 'HOT' "
                + "GROUP BY hr_version HAVING count(*) > 1) d")).isZero();
        // events were emitted in strictly increasing version order (each applied change was newer)
        List<Long> versions = jdbc.queryForList(
                "SELECT hr_version FROM outbox_event WHERE employee_id = 'HOT' ORDER BY id", Long.class);
        assertThat(versions).isSorted();
    }
}
