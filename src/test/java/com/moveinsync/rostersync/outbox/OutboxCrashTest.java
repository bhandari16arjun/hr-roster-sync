package com.moveinsync.rostersync.outbox;

import com.moveinsync.rostersync.booking.BookingRepository;
import com.moveinsync.rostersync.domain.ChangeEventType;
import com.moveinsync.rostersync.domain.ChangeSource;
import com.moveinsync.rostersync.domain.HrChange;
import com.moveinsync.rostersync.ops.CrashPoint;
import com.moveinsync.rostersync.ops.SimulatedCrashException;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.sync.EmployeeSyncService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Crash the process between the database write and the event publish" - three ways.
 * The crash is simulated by an exception thrown at the crash point, which aborts the in-flight
 * transaction exactly as a dead process would (Postgres rolls back an aborted connection's work).
 */
class OutboxCrashTest extends IntegrationTest {

    @Autowired
    EmployeeSyncService sync;
    @Autowired
    OutboxRelay relay;
    @Autowired
    BookingRepository bookings;
    @Autowired
    MeterRegistry metrics;
    @Autowired
    OutboxRepository outbox;

    /** Fails publishing for employees whose id starts with FAIL, to test retries and dead-lettering. */
    @TestConfiguration
    static class FlakyConsumer {
        @Bean
        ChangeEventHandler flakyHandler() {
            return new ChangeEventHandler() {
                public String name() {
                    return "flaky";
                }

                public boolean handles(ChangeEventType type) {
                    return true;
                }

                public void handle(OutboxEvent event) {
                    if (event.employeeId().startsWith("FAIL")) {
                        throw new IllegalStateException("downstream unavailable");
                    }
                }
            };
        }
    }

    private void employeeWithFutureBooking(String id) {
        sync.applyOne(upsert(id, 1, "BLR-01", "S1"));
        bookings.create(ACME, id, LocalDate.now().plusDays(2), "3A");
        bookings.create(ACME, id, LocalDate.now().plusDays(3), "3A");
        relay.drain(); // publish the CREATED event
    }

    @Test
    void crashInsideTheSyncTransactionLeavesNeitherTheChangeNorTheEvent() {
        faults.arm(CrashPoint.SYNC_BEFORE_OUTBOX_WRITE);
        assertThatThrownBy(() -> sync.applyOne(upsert("E1", 1, "BLR-01", "S1")))
                .isInstanceOf(SimulatedCrashException.class);

        // atomic: the employee row written before the crash was rolled back together with the outbox row
        assertThat(count("SELECT count(*) FROM employee")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_event")).isZero();

        // HR retries (at-least-once delivery from the source) -> applied exactly once
        assertThat(sync.applyOne(upsert("E1", 1, "BLR-01", "S1")).outcome()).isEqualTo("APPLIED");
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(1);
    }

    @Test
    void crashAfterCommitBeforePublishIsRecoveredByTheRelay() {
        employeeWithFutureBooking("E1");
        sync.applyOne(HrChange.deactivate(ACME, "E1", 2, ChangeSource.API)); // committed with its outbox row

        faults.arm(CrashPoint.RELAY_BEFORE_PUBLISH);
        assertThatThrownBy(() -> relay.drain()).isInstanceOf(SimulatedCrashException.class);

        // the employee is inactive but nothing was published yet: bookings still confirmed
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM booking WHERE status = 'CONFIRMED'")).isEqualTo(2);

        // "restart": the relay runs again and finds the pending event in the table
        relay.drain();
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")).isZero();
        assertThat(count("SELECT count(*) FROM booking WHERE status = 'CANCELLED' AND seat_no IS NULL")).isEqualTo(2);
    }

    @Test
    void crashAfterPublishBeforeMarkRedeliversAndConsumerDedups() {
        employeeWithFutureBooking("E1");
        sync.applyOne(HrChange.deactivate(ACME, "E1", 2, ChangeSource.API));
        double dupBefore = metrics.counter("rostersync.consumer.duplicates", "consumer", "booking-cancellation").count();

        faults.arm(CrashPoint.RELAY_AFTER_PUBLISH_BEFORE_MARK);
        assertThatThrownBy(() -> relay.drain()).isInstanceOf(SimulatedCrashException.class);

        // the consumer already committed its side effect, but the relay never marked the event
        assertThat(count("SELECT count(*) FROM booking WHERE status = 'CANCELLED'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")).isEqualTo(1);

        relay.drain(); // re-delivery
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")).isZero();
        assertThat(count("SELECT count(*) FROM processed_event WHERE consumer = 'booking-cancellation'")).isEqualTo(1);
        assertThat(metrics.counter("rostersync.consumer.duplicates", "consumer", "booking-cancellation").count())
                .isEqualTo(dupBefore + 1);
        assertThat(count("SELECT count(*) FROM booking WHERE status = 'CANCELLED'")).isEqualTo(2);
    }

    @Test
    void failingPublishRetriesWithBackoffThenDeadLettersAndCanBeReplayed() {
        sync.applyOne(upsert("FAIL-1", 1, "BLR-01", "S1"));
        sync.applyOne(upsert("OK-1", 1, "BLR-01", "S1"));

        for (int i = 0; i < 8; i++) {
            relay.drain();
            jdbc.update("UPDATE outbox_event SET next_attempt_at = now() WHERE status = 'PENDING'"); // skip the wait
        }
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE employee_id = 'FAIL-1'", String.class))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE employee_id = 'FAIL-1'", Integer.class))
                .isEqualTo(8);
        // once the bad event is dead-lettered, later events are no longer blocked
        relay.drain();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE employee_id = 'OK-1'", String.class))
                .isEqualTo("PUBLISHED");

        // operator replays the dead letter
        assertThat(outbox.replayDead(ACME, java.time.Instant.now())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE employee_id = 'FAIL-1'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void backoffGrowsExponentiallyWithJitter() {
        long a1 = relay.backoffMillis(1);
        long a6 = relay.backoffMillis(6);
        assertThat(a1).isBetween(500L, 500L + 1000L);
        assertThat(a6).isBetween(500L, 500L + 32_000L);
    }
}
