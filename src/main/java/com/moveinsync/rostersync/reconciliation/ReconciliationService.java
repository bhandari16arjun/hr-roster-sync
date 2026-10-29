package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.common.error.BadRequestException;
import com.moveinsync.rostersync.common.error.ConflictException;
import com.moveinsync.rostersync.common.error.NotFoundException;
import com.moveinsync.rostersync.config.AppProperties;
import com.moveinsync.rostersync.domain.Buckets;
import com.moveinsync.rostersync.domain.ChangeSource;
import com.moveinsync.rostersync.domain.HrChange;
import com.moveinsync.rostersync.domain.HrRecord;
import com.moveinsync.rostersync.domain.SafetyBrake;
import com.moveinsync.rostersync.domain.validation.FieldError;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import com.moveinsync.rostersync.employee.EmployeeRepository;
import com.moveinsync.rostersync.sync.ApplyResult;
import com.moveinsync.rostersync.sync.EmployeeSyncService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Nightly reconciliation of the full HR export against the store.
 *
 *  Phase 1  SPILL    stream the CSV once; validate every row; write valid rows into 64 bucket files.
 *                    Invalid rows go to the per-record error report and never stop the run.
 *  Phase 2  COMPARE  for each bucket: load that bucket's rows (~1/64 of the file) and stream the
 *                    same bucket from the DB; classify rows; write rows to apply into plan.csv and
 *                    MISSING employees into reconciliation_missing. Nothing is written to employees yet.
 *  Phase 3  BRAKE    if too many employees are missing (or too many rows invalid, or the trailer row
 *                    count does not match), stop: status AWAITING_APPROVAL, nobody is touched.
 *  Phase 4  APPLY    apply plan.csv in chunks through EmployeeSyncService (same version/ownership rules
 *                    as the API) and deactivate the missing employees whose version did not change.
 *
 * Re-running the same file is safe: every row goes through the version rule, so a replay is a no-op.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository runs;
    private final EmployeeRepository employees;
    private final EmployeeSyncService sync;
    private final RecordValidator validator;
    private final SafetyBrake brake;
    private final AppProperties.Reconciliation config;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final BucketComparator comparator = new BucketComparator();

    public ReconciliationService(ReconciliationRepository runs, EmployeeRepository employees, EmployeeSyncService sync,
                                 RecordValidator validator, SafetyBrake brake, AppProperties props, Clock clock,
                                 MeterRegistry metrics) {
        this.runs = runs;
        this.employees = employees;
        this.sync = sync;
        this.validator = validator;
        this.brake = brake;
        this.config = props.reconciliation();
        this.clock = clock;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        List<UUID> failed = runs.failInterrupted(clock.instant());
        if (!failed.isEmpty()) {
            log.warn("marked interrupted reconciliation runs as FAILED count={} runIds={}", failed.size(), failed);
        }
    }

    /** Runs phases 1-4 synchronously and returns the final run report. */
    public ReconciliationRun reconcile(String tenantId, InputStream csv) {
        UUID runId = UUID.randomUUID();
        Path workDir = Path.of(config.workDir()).toAbsolutePath().resolve(runId.toString());
        try {
            runs.insertRunning(runId, tenantId, workDir.toString(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("RUN_IN_PROGRESS", "a reconciliation is already running for this client");
        }
        List<UUID> superseded = runs.supersedeAwaiting(tenantId);
        MDC.put("runId", runId.toString());
        Timer.Sample total = Timer.start(metrics);
        try {
            log.info("reconciliation started tenant={} superseded={}", tenantId, superseded);
            SpillResult spill = spill(runId, tenantId, csv, workDir);

            long t0 = System.nanoTime();
            BucketComparator.Counts counts = compareAll(runId, tenantId, workDir, spill.invalidIds());
            long compareMillis = (System.nanoTime() - t0) / 1_000_000;
            runs.saveCompareResult(runId, spill.fileRows, spill.declaredRows, spill.invalidRows, counts, compareMillis);
            log.info("reconciliation compared fileRows={} invalid={} active={} new={} changed={} unchanged={} "
                            + "stale={} conflicts={} missing={} compareMillis={}",
                    spill.fileRows, spill.invalidRows, counts.activeBefore, counts.newRows, counts.changed,
                    counts.unchanged, counts.stale, counts.conflicts, counts.missing, compareMillis);

            SafetyBrake.Decision decision = brake.evaluate(new SafetyBrake.Counts(counts.activeBefore, counts.missing,
                    spill.fileRows, spill.invalidRows, spill.declaredRows));
            if (decision.tripped()) {
                runs.brake(runId, decision.reason(), clock.instant());
                metrics.counter("rostersync.reconciliation.brake_tripped").increment();
                // ALERT: this ERROR line + the counter above are what pages the on-call operator
                log.error("SAFETY BRAKE TRIPPED: reconciliation stopped, nobody deactivated, operator approval "
                        + "required tenant={} reason=\"{}\"", tenantId, decision.reason());
                return get(tenantId, runId);
            }
            runs.transition(runId, ReconciliationRun.RUNNING, ReconciliationRun.APPLYING);
            applyRun(runId, tenantId, workDir);
            return get(tenantId, runId);
        } catch (RuntimeException e) {
            runs.fail(runId, "failed: " + e.getClass().getSimpleName(), clock.instant());
            SpillFiles.deleteQuietly(workDir);
            log.error("reconciliation failed tenant={} error={}", tenantId, e.getClass().getSimpleName(), e);
            throw e;
        } finally {
            total.stop(metrics.timer("rostersync.reconciliation.duration"));
            MDC.remove("runId");
        }
    }

    /** Operator approves a braked run: apply it exactly as if the brake had not tripped. */
    public ReconciliationRun approve(String tenantId, UUID runId, String operator) {
        ReconciliationRun run = get(tenantId, runId);
        if (!ReconciliationRun.AWAITING_APPROVAL.equals(run.status())) {
            throw new ConflictException("RUN_NOT_AWAITING_APPROVAL", "only runs awaiting approval can be approved");
        }
        boolean moved;
        try {
            moved = runs.transition(runId, ReconciliationRun.AWAITING_APPROVAL, ReconciliationRun.APPLYING);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("RUN_IN_PROGRESS", "another reconciliation is running for this client");
        }
        if (!moved) {
            throw new ConflictException("RUN_NOT_AWAITING_APPROVAL", "the run changed state; reload and retry");
        }
        runs.decide(runId, operator, clock.instant());
        log.warn("braked reconciliation approved by operator runId={} operator={}", runId, operator);
        MDC.put("runId", runId.toString());
        try {
            applyRun(runId, tenantId, Path.of(runs.workDir(runId)));
        } catch (RuntimeException e) {
            runs.fail(runId, "failed: " + e.getClass().getSimpleName(), clock.instant());
            throw e;
        } finally {
            MDC.remove("runId");
        }
        return get(tenantId, runId);
    }

    public ReconciliationRun reject(String tenantId, UUID runId, String operator) {
        ReconciliationRun run = get(tenantId, runId);
        if (!runs.transition(runId, ReconciliationRun.AWAITING_APPROVAL, ReconciliationRun.REJECTED)) {
            throw new ConflictException("RUN_NOT_AWAITING_APPROVAL", "only runs awaiting approval can be rejected");
        }
        runs.decide(runId, operator, clock.instant());
        SpillFiles.deleteQuietly(Path.of(runs.workDir(run.id())));
        log.info("braked reconciliation rejected runId={} operator={}", runId, operator);
        return get(tenantId, runId);
    }

    public ReconciliationRun get(String tenantId, UUID runId) {
        return runs.find(tenantId, runId).orElseThrow(() -> new NotFoundException("reconciliation run"));
    }

    // ---------------------------------------------------------------- phase 1: spill

    /** invalidIds: employees present in the file with an invalid row (bounded by the invalid-ratio brake). */
    private record SpillResult(int fileRows, int invalidRows, Integer declaredRows, Set<String> invalidIds) {
    }

    private SpillResult spill(UUID runId, String tenantId, InputStream csv, Path workDir) {
        int fileRows = 0;
        int invalid = 0;
        Integer declared = null;
        List<ReconciliationRepository.RowError> errorBuffer = new ArrayList<>();
        int storedErrors = 0;
        Set<String> invalidIds = new HashSet<>();
        try (Reader reader = new InputStreamReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = SnapshotFormat.READ.parse(reader);
             SpillFiles.BucketWriter buckets = new SpillFiles.BucketWriter(workDir)) {
            Set<String> header = new HashSet<>(parser.getHeaderNames());
            for (String required : SnapshotFormat.HEADER) {
                if (!header.contains(required)) {
                    throw new BadRequestException("BAD_HEADER", "the export header must contain: "
                            + String.join(",", SnapshotFormat.HEADER));
                }
            }
            for (CSVRecord rec : parser) {
                if (rec.size() > 0 && SnapshotFormat.TRAILER_MARKER.equals(rec.get(0))) {
                    declared = parseIntOrNull(rec.size() > 1 ? rec.get(1) : null);
                    continue;
                }
                fileRows++;
                long rowNumber = rec.getRecordNumber() + 1; // +1 for the header line
                List<FieldError> errors = new ArrayList<>();
                SnapshotRow row = parseRow(tenantId, rec, rowNumber, errors);
                if (row == null) {
                    invalid++;
                    String safeId = idIfValid(rec);
                    if (safeId != null) {
                        invalidIds.add(safeId);
                    }
                    for (FieldError fe : errors) {
                        if (storedErrors < config.maxStoredErrors()) {
                            errorBuffer.add(new ReconciliationRepository.RowError(rowNumber, safeId, fe));
                            storedErrors++;
                        }
                    }
                    if (errorBuffer.size() >= 500) {
                        runs.insertErrors(runId, errorBuffer);
                        errorBuffer.clear();
                    }
                    continue;
                }
                buckets.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UncheckedIOException | IllegalStateException e) {
            // commons-csv throws these for a broken quote / unreadable stream (e.g. a cut-off file)
            throw new BadRequestException("UNREADABLE_FILE", "the export could not be parsed as CSV");
        }
        runs.insertErrors(runId, errorBuffer);
        metrics.counter("rostersync.reconciliation.rows", "result", "valid").increment(fileRows - invalid);
        metrics.counter("rostersync.reconciliation.rows", "result", "invalid").increment(invalid);
        return new SpillResult(fileRows, invalid, declared, invalidIds);
    }

    private SnapshotRow parseRow(String tenantId, CSVRecord rec, long rowNumber, List<FieldError> errors) {
        if (!rec.isConsistent()) {
            errors.add(new FieldError("row", "INCOMPLETE_ROW", "row does not have the same number of columns as the header"));
            return null;
        }
        String employeeId = rec.get("employeeId").strip();
        Long version = parseLongOrNull(rec.get("version"));
        errors.addAll(validator.validateIdentity(employeeId, version));
        HrRecord record = new HrRecord(rec.get("name"), rec.get("email"), rec.get("phone"), rec.get("managerId"),
                rec.get("officeCode"), rec.get("shiftCode"));
        errors.addAll(validator.validateRecord(tenantId, record));
        return errors.isEmpty() ? new SnapshotRow(rowNumber, employeeId, version, record) : null;
    }

    private static String idIfValid(CSVRecord rec) {
        if (rec.size() == 0) return null;
        String id = rec.get(0).strip();
        return RecordValidator.EMPLOYEE_ID.matcher(id).matches() ? id : null;
    }

    // ---------------------------------------------------------------- phase 2: compare

    private BucketComparator.Counts compareAll(UUID runId, String tenantId, Path workDir, Set<String> invalidIds) {
        BucketComparator.Counts total = new BucketComparator.Counts();
        List<ReconciliationRepository.RowError> conflicts = new ArrayList<>();
        try (SpillFiles.PlanWriter plan = new SpillFiles.PlanWriter(workDir)) {
            for (int b = 0; b < Buckets.BUCKET_COUNT; b++) {
                Path bucketFile = SpillFiles.bucketFile(workDir, b);
                int bucket = b;
                List<Map.Entry<String, Long>> missing = new ArrayList<>();
                BucketComparator.Counts c = comparator.compare(
                        sink -> SpillFiles.readRows(bucketFile, sink),
                        sink -> employees.streamBucket(tenantId, bucket, sink),
                        invalidIds::contains,
                        new BucketComparator.Sink() {
                            @Override
                            public void apply(SnapshotRow row) {
                                try {
                                    plan.write(row);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }

                            @Override
                            public void missing(EmployeeRepository.StoredDigest stored) {
                                missing.add(new AbstractMap.SimpleEntry<>(stored.employeeId(), stored.hrVersion()));
                            }

                            @Override
                            public void conflict(SnapshotRow row) {
                                conflicts.add(new ReconciliationRepository.RowError(row.rowNumber(), row.employeeId(),
                                        new FieldError("version", "VERSION_CONFLICT",
                                                "same version as stored but different data")));
                            }
                        });
                runs.insertMissing(runId, missing);
                total.add(c);
                SpillFiles.deleteQuietly(bucketFile); // the bucket is no longer needed
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!conflicts.isEmpty()) {
            runs.insertErrors(runId, conflicts.subList(0, Math.min(conflicts.size(), config.maxStoredErrors())));
        }
        return total;
    }

    // ---------------------------------------------------------------- phase 4: apply

    private void applyRun(UUID runId, String tenantId, Path workDir) {
        int[] applied = {0};
        List<HrChange> chunk = new ArrayList<>(config.chunkSize());
        SpillFiles.readRows(SpillFiles.planFile(workDir), row -> {
            chunk.add(HrChange.upsert(tenantId, row.employeeId(), row.version(), row.record(), ChangeSource.SNAPSHOT));
            if (chunk.size() >= config.chunkSize()) {
                applied[0] += countApplied(sync.apply(tenantId, chunk));
                chunk.clear();
            }
        });
        applied[0] += countApplied(sync.apply(tenantId, chunk));

        int deactivated = 0;
        String after = "";
        while (true) {
            Map<String, Long> page = runs.missingPage(runId, after, config.chunkSize());
            if (page.isEmpty()) {
                break;
            }
            deactivated += sync.deactivateMissing(tenantId, page);
            after = page.keySet().stream().reduce((a, b) -> b).orElseThrow();
        }
        runs.complete(runId, applied[0], deactivated, clock.instant());
        SpillFiles.deleteQuietly(workDir);
        log.info("reconciliation completed tenant={} applied={} deactivated={}", tenantId, applied[0], deactivated);
    }

    private static int countApplied(List<ApplyResult> results) {
        return (int) results.stream().filter(r -> "APPLIED".equals(r.outcome()) || "VERSION_ONLY".equals(r.outcome()))
                .count();
    }

    private static Long parseLongOrNull(String s) {
        try {
            return s == null ? null : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String s) {
        Long l = parseLongOrNull(s);
        return l == null ? null : l.intValue();
    }
}
