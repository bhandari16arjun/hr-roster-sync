package com.moveinsync.rostersync.booking;

import com.moveinsync.rostersync.domain.ChangeEventType;
import com.moveinsync.rostersync.outbox.ChangeEventHandler;
import com.moveinsync.rostersync.outbox.OutboxEvent;
import com.moveinsync.rostersync.outbox.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Deactivation side effect: when an employee leaves, cancel their future cab bookings and free the
 * seats. Dedup by eventId (processed_event table) in the same transaction as the cancellation, so a
 * re-delivered event is a no-op.
 */
@Component
public class BookingCancellationHandler implements ChangeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingCancellationHandler.class);
    public static final String NAME = "booking-cancellation";

    private final BookingRepository bookings;
    private final ProcessedEventRepository processed;
    private final Clock clock;
    private final MeterRegistry metrics;

    public BookingCancellationHandler(BookingRepository bookings, ProcessedEventRepository processed, Clock clock,
                                      MeterRegistry metrics) {
        this.bookings = bookings;
        this.processed = processed;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean handles(ChangeEventType type) {
        return type == ChangeEventType.EMPLOYEE_DEACTIVATED;
    }

    /**
     * REQUIRES_NEW: the consumer commits independently of the relay, exactly like a separate
     * service would. So if the relay dies after this commit, the event is re-delivered and the
     * processed_event check turns the second delivery into a no-op.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OutboxEvent event) {
        if (!processed.markProcessed(NAME, event.eventId())) {
            metrics.counter("rostersync.consumer.duplicates", "consumer", NAME).increment();
            log.info("duplicate event ignored consumer={} eventId={}", NAME, event.eventId());
            return;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int cancelled = bookings.cancelFuture(event.tenantId(), event.employeeId(), today, "EMPLOYEE_DEACTIVATED",
                clock.instant());
        metrics.counter("rostersync.bookings.cancelled").increment(cancelled);
        log.info("future bookings cancelled tenant={} employeeId={} count={}", event.tenantId(),
                event.employeeId(), cancelled);
    }
}
