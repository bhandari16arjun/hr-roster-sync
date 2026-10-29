package com.moveinsync.rostersync.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.rostersync.domain.ChangeSource;
import com.moveinsync.rostersync.domain.EmployeeMerger;
import com.moveinsync.rostersync.domain.EmployeeState;
import com.moveinsync.rostersync.domain.HrChange;
import com.moveinsync.rostersync.domain.MergeDecision;
import com.moveinsync.rostersync.employee.EmployeeRepository;
import com.moveinsync.rostersync.ops.CrashPoint;
import com.moveinsync.rostersync.ops.FaultInjector;
import com.moveinsync.rostersync.ops.SimulatedCrashException;
import com.moveinsync.rostersync.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies HR changes to the store. This is where ordering, idempotency, ownership and the outbox
 * meet. One call = one database transaction:
 *
 *   1. lock the affected employee rows        (SELECT ... FOR UPDATE, sorted to avoid deadlocks)
 *   2. decide per change with EmployeeMerger   (pure logic: stale / duplicate / conflict / apply)
 *   3. write the new employee states           (only HR columns)
 *   4. write one outbox row per real change    (same transaction -> can never disagree with step 3)
 *   5. commit
 *
 * Because the row lock serialises all writers of one employee, the API path and the nightly
 * reconciliation can run at the same time safely: whoever commits second sees the first one's
 * version and the version rule decides.
 */
@Service
public class EmployeeSyncService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeSyncService.class);
    private static final int MAX_RACE_RETRIES = 3;

    private final EmployeeRepository employees;
    private final OutboxRepository outbox;
    private final EmployeeMerger merger;
    private final TransactionTemplate tx;
    private final FaultInjector faults;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Timer applyTimer;

    public EmployeeSyncService(EmployeeRepository employees, OutboxRepository outbox, EmployeeMerger merger,
                               TransactionTemplate tx, FaultInjector faults, ObjectMapper json, Clock clock,
                               MeterRegistry metrics) {
        this.employees = employees;
        this.outbox = outbox;
        this.merger = merger;
        this.tx = tx;
        this.faults = faults;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
        this.applyTimer = Timer.builder("rostersync.apply.latency")
                .description("time to apply one batch of HR changes in one transaction")
                .register(metrics);
    }

    /**
     * Applies a batch of changes for one tenant in a single transaction. If the batch hits an
     * unexpected database error, it falls back to one transaction per change so that one bad
     * record can never fail the whole batch.
     *
     * @return one result per input change, in input order
     */
    public List<ApplyResult> apply(String tenantId, List<HrChange> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }
        Timer.Sample sample = Timer.start(metrics);
        try {
            return applyWithRaceRetry(tenantId, changes);
        } catch (SimulatedCrashException crash) {
            throw crash; // a "crash" must not be swallowed by the fallback
        } catch (DataAccessException e) {
            log.warn("batch apply failed, falling back to per-record transactions tenant={} size={} error={}",
                    tenantId, changes.size(), e.getClass().getSimpleName());
            List<ApplyResult> results = new ArrayList<>(changes.size());
            for (HrChange c : changes) {
                try {
                    results.add(applyWithRaceRetry(tenantId, List.of(c)).get(0));
                } catch (DataAccessException single) {
                    log.error("record apply failed tenant={} employeeId={} version={} error={}",
                            tenantId, c.employeeId(), c.version(), single.getClass().getSimpleName());
                    results.add(record(c, new ApplyResult(c.employeeId(), c.version(), "FAILED", "STORAGE_ERROR")));
                }
            }
            return results;
        } finally {
            sample.stop(applyTimer);
        }
    }

    public ApplyResult applyOne(HrChange change) {
        return apply(change.tenantId(), List.of(change)).get(0);
    }

    /** Deactivates employees found missing by a reconciliation run, unless they changed since. */
    public int deactivateMissing(String tenantId, Map<String, Long> expectedVersionById) {
        if (expectedVersionById.isEmpty()) {
            return 0;
        }
        Integer n = tx.execute(status -> {
            Instant now = clock.instant();
            List<EmployeeState> locked = employees.findAllForUpdate(tenantId, expectedVersionById.keySet());
            List<EmployeeState> updates = new ArrayList<>();
            List<OutboxRepository.NewEvent> events = new ArrayList<>();
            for (EmployeeState current : locked) {
                MergeDecision d = merger.mergeReconciliationDeactivate(Optional.of(current),
                        expectedVersionById.get(current.employeeId()));
                countOutcome(ChangeSource.RECONCILIATION, d.outcome().name());
                if (d instanceof MergeDecision.Apply a) {
                    updates.add(a.newState());
                    events.add(newEvent(a, ChangeSource.RECONCILIATION));
                }
            }
            employees.updateHr(updates, now);
            outbox.append(events, now);
            return updates.size();
        });
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------------------------

    private List<ApplyResult> applyWithRaceRetry(String tenantId, List<HrChange> changes) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> applyInTransaction(tenantId, changes));
            } catch (InsertRaceException race) {
                // another transaction created one of "our new" employees between our read and insert;
                // the transaction rolled back, so simply retry: this time the row exists and gets locked.
                if (attempt >= MAX_RACE_RETRIES) {
                    throw new org.springframework.dao.ConcurrencyFailureException("insert race not resolved", race);
                }
                metrics.counter("rostersync.apply.race_retries").increment();
            }
        }
    }

    private List<ApplyResult> applyInTransaction(String tenantId, List<HrChange> changes) {
        Instant now = clock.instant();
        Set<String> ids = new LinkedHashSet<>();
        changes.forEach(c -> ids.add(c.employeeId()));

        // 1. lock existing rows
        Map<String, EmployeeState> working = new HashMap<>();
        for (EmployeeState s : employees.findAllForUpdate(tenantId, ids)) {
            working.put(s.employeeId(), s);
        }
        Set<String> existedBefore = Set.copyOf(working.keySet());

        // 2. decide. Within one batch, changes of the same employee are applied in version order.
        Integer[] order = new Integer[changes.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparing((Integer i) -> changes.get(i).employeeId())
                .thenComparingLong(i -> changes.get(i).version()));

        ApplyResult[] results = new ApplyResult[changes.size()];
        Map<String, EmployeeState> dirty = new LinkedHashMap<>();
        List<OutboxRepository.NewEvent> events = new ArrayList<>();
        for (int i : order) {
            HrChange change = changes.get(i);
            MergeDecision decision = merger.merge(Optional.ofNullable(working.get(change.employeeId())), change);
            results[i] = toResult(change, decision);
            if (decision instanceof MergeDecision.Apply a) {
                working.put(change.employeeId(), a.newState());
                dirty.put(change.employeeId(), a.newState());
                if (a.eventType() != null) {
                    events.add(newEvent(a, change.source()));
                }
            }
        }

        // 3. write employee rows
        List<EmployeeState> inserts = new ArrayList<>();
        List<EmployeeState> updates = new ArrayList<>();
        dirty.forEach((id, state) -> (existedBefore.contains(id) ? updates : inserts).add(state));
        int[] inserted = employees.insertIfAbsent(inserts, now);
        for (int r : inserted) {
            if (r == 0) {
                throw new InsertRaceException();
            }
        }
        employees.updateHr(updates, now);

        faults.maybeCrash(CrashPoint.SYNC_BEFORE_OUTBOX_WRITE);

        // 4. outbox rows in the same transaction
        outbox.append(events, now);

        for (ApplyResult r : results) {
            countOutcome(changes.get(0).source(), r.outcome());
        }
        return List.of(results);
    }

    private ApplyResult toResult(HrChange c, MergeDecision d) {
        return switch (d) {
            case MergeDecision.Apply a -> new ApplyResult(c.employeeId(), c.version(), d.outcome().name(), null);
            case MergeDecision.Stale s -> new ApplyResult(c.employeeId(), c.version(), "STALE", "OLDER_THAN_STORED_VERSION");
            case MergeDecision.Duplicate dup -> new ApplyResult(c.employeeId(), c.version(), "DUPLICATE", null);
            case MergeDecision.Conflict cf -> {
                log.warn("version conflict tenant={} employeeId={} version={} reason={}",
                        c.tenantId(), c.employeeId(), c.version(), cf.reasonCode());
                yield new ApplyResult(c.employeeId(), c.version(), "CONFLICT", cf.reasonCode());
            }
        };
    }

    private OutboxRepository.NewEvent newEvent(MergeDecision.Apply a, ChangeSource source) {
        EmployeeState s = a.newState();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("type", a.eventType().name());
        payload.put("tenantId", s.tenantId());
        payload.put("employeeId", s.employeeId());
        payload.put("version", s.hrVersion());
        payload.put("status", s.status().name());
        payload.put("changedFields", a.changedFields());
        payload.put("source", source.name());
        if (s.hrRecord() != null) {
            payload.put("officeCode", s.hrRecord().officeCode());
            payload.put("shiftCode", s.hrRecord().shiftCode());
        }
        return new OutboxRepository.NewEvent(eventId, s.tenantId(), s.employeeId(), a.eventType(), s.hrVersion(),
                toJson(payload));
    }

    String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise event payload", e);
        }
    }

    private ApplyResult record(HrChange c, ApplyResult r) {
        countOutcome(c.source(), r.outcome());
        return r;
    }

    private void countOutcome(ChangeSource source, String outcome) {
        metrics.counter("rostersync.changes", "source", source.name(), "outcome", outcome).increment();
    }

    /** Signals that an INSERT lost a race; causes rollback and a retry of the batch. */
    static final class InsertRaceException extends RuntimeException {
        InsertRaceException() {
            super("concurrent insert detected", null, false, false);
        }
    }
}
