package com.moveinsync.rostersync.outbox;

import com.moveinsync.rostersync.config.AppProperties;
import com.moveinsync.rostersync.ops.CrashPoint;
import com.moveinsync.rostersync.ops.FaultInjector;
import com.moveinsync.rostersync.ops.SimulatedCrashException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Moves events from the outbox table to the publisher.
 *
 * Loop (every poll interval):
 *   BEGIN; claim up to N due rows (FOR UPDATE SKIP LOCKED, oldest first); publish each in order;
 *   mark them PUBLISHED; COMMIT.
 *
 * If the process dies after publishing but before COMMIT, the rows are still PENDING and will be
 * published again: that is why delivery is at-least-once and consumers dedup by eventId.
 *
 * Failures: the failing event gets exponential backoff with jitter; after maxAttempts it becomes
 * DEAD (dead-letter) and an operator can inspect and replay it. Processing of the batch stops at the
 * first failure so later events of the same employee are not published before an earlier one.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final ChangeEventPublisher publisher;
    private final TransactionTemplate tx;
    private final FaultInjector faults;
    private final Clock clock;
    private final AppProperties.Outbox config;
    private final MeterRegistry metrics;
    private final AtomicReference<OutboxRepository.Stats> lastStats =
            new AtomicReference<>(new OutboxRepository.Stats(0, 0, 0));

    public OutboxRelay(OutboxRepository outbox, ChangeEventPublisher publisher, TransactionTemplate tx,
                       FaultInjector faults, Clock clock, AppProperties props, MeterRegistry metrics) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = tx;
        this.faults = faults;
        this.clock = clock;
        this.config = props.outbox();
        this.metrics = metrics;
        Gauge.builder("rostersync.outbox.pending", lastStats, s -> s.get().pending())
                .description("events waiting to be published").register(metrics);
        Gauge.builder("rostersync.outbox.dead", lastStats, s -> s.get().dead())
                .description("events in the dead-letter state").register(metrics);
        Gauge.builder("rostersync.outbox.lag.seconds", lastStats, s -> s.get().oldestPendingAgeSeconds())
                .description("age of the oldest unpublished event").register(metrics);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:200}")
    public void scheduledRun() {
        if (!config.relayEnabled()) {
            return;
        }
        try {
            drain();
        } catch (SimulatedCrashException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("outbox relay run failed error={}", e.getClass().getSimpleName(), e);
        }
    }

    /** Publishes everything that is due. Returns the number of events published. */
    public int drain() {
        int total = 0;
        while (true) {
            Integer published = tx.execute(status -> publishOneBatch());
            int n = published == null ? 0 : published;
            total += n;
            if (n < config.batchSize()) {
                break;
            }
        }
        refreshStats();
        return total;
    }

    private int publishOneBatch() {
        Instant now = clock.instant();
        List<OutboxEvent> due = outbox.claimDue(config.batchSize(), now);
        List<Long> done = new ArrayList<>();
        for (OutboxEvent event : due) {
            faults.maybeCrash(CrashPoint.RELAY_BEFORE_PUBLISH);
            try {
                publisher.publish(event);
                done.add(event.id());
            } catch (SimulatedCrashException crash) {
                throw crash;
            } catch (RuntimeException e) {
                int attempts = event.attempts() + 1;
                boolean dead = attempts >= config.maxAttempts();
                outbox.markFailed(event.id(), attempts, now.plusMillis(backoffMillis(attempts)),
                        e.getClass().getSimpleName(), dead);
                metrics.counter("rostersync.outbox.publish_failures").increment();
                if (dead) {
                    metrics.counter("rostersync.outbox.dead_lettered").increment();
                    log.error("outbox event dead-lettered eventId={} type={} attempts={}", event.eventId(),
                            event.type(), attempts);
                } else {
                    log.warn("outbox publish failed, will retry eventId={} attempts={}", event.eventId(), attempts);
                }
                break; // keep per-employee order: do not publish later events past a failed one
            }
            faults.maybeCrash(CrashPoint.RELAY_AFTER_PUBLISH_BEFORE_MARK);
        }
        outbox.markPublished(done, now);
        metrics.counter("rostersync.outbox.published").increment(done.size());
        return done.size() == due.size() ? due.size() : 0; // stop draining after a failure
    }

    /** Exponential backoff with full jitter: random(0, base * 2^attempts), capped at 5 minutes. */
    long backoffMillis(int attempts) {
        long cap = 300_000;
        long exp = Math.min(cap, config.baseBackoffMs() * (1L << Math.min(attempts, 20)));
        return config.baseBackoffMs() + ThreadLocalRandom.current().nextLong(exp);
    }

    private void refreshStats() {
        try {
            lastStats.set(outbox.stats(clock.instant()));
        } catch (RuntimeException e) {
            log.warn("could not refresh outbox stats error={}", e.getClass().getSimpleName());
        }
    }

    public OutboxRepository.Stats stats() {
        refreshStats();
        return lastStats.get();
    }
}
