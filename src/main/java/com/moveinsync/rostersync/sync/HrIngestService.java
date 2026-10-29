package com.moveinsync.rostersync.sync;

import com.moveinsync.rostersync.common.error.BadRequestException;
import com.moveinsync.rostersync.domain.ChangeSource;
import com.moveinsync.rostersync.domain.HrChange;
import com.moveinsync.rostersync.domain.HrRecord;
import com.moveinsync.rostersync.domain.validation.FieldError;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Real-time ingest: validate each event at the boundary, then apply all valid ones in one batch.
 * One bad record never fails the batch - it is reported with its index and error codes.
 */
@Service
public class HrIngestService {

    public static final int MAX_BATCH = 1000;

    private final RecordValidator validator;
    private final EmployeeSyncService sync;
    private final MeterRegistry metrics;

    public HrIngestService(RecordValidator validator, EmployeeSyncService sync, MeterRegistry metrics) {
        this.validator = validator;
        this.sync = sync;
        this.metrics = metrics;
    }

    public IngestReport ingest(String tenantId, List<HrEventDto> events) {
        if (events == null || events.isEmpty()) {
            throw new BadRequestException("EMPTY_BATCH", "the batch must contain at least one event");
        }
        if (events.size() > MAX_BATCH) {
            throw new BadRequestException("BATCH_TOO_LARGE", "a batch may contain at most " + MAX_BATCH + " events");
        }
        List<IngestReport.ItemError> errors = new ArrayList<>();
        List<IngestReport.ItemError> warnings = new ArrayList<>();
        IngestReport.Item[] items = new IngestReport.Item[events.size()];
        List<HrChange> valid = new ArrayList<>();
        List<Integer> validIndex = new ArrayList<>();

        for (int i = 0; i < events.size(); i++) {
            HrEventDto e = events.get(i);
            List<FieldError> fieldErrors = new ArrayList<>();
            HrChange change = toChange(tenantId, e, fieldErrors);
            if (e != null && (e.pickupPoint() != null || e.specialAssistance() != null)) {
                warnings.add(new IngestReport.ItemError(i, safeId(e), "pickupPoint/specialAssistance",
                        "FIELD_NOT_OWNED", "transport-owned fields are ignored in HR events"));
            }
            if (change == null) {
                for (FieldError fe : fieldErrors) {
                    errors.add(new IngestReport.ItemError(i, safeId(e), fe.field(), fe.code(), fe.message()));
                    metrics.counter("rostersync.validation.rejected", "code", fe.code()).increment();
                }
                items[i] = new IngestReport.Item(i, safeId(e), e == null ? null : e.version(), "REJECTED",
                        "VALIDATION_FAILED");
                metrics.counter("rostersync.changes", "source", "API", "outcome", "REJECTED").increment();
            } else {
                valid.add(change);
                validIndex.add(i);
            }
        }

        List<ApplyResult> results = sync.apply(tenantId, valid);
        for (int k = 0; k < results.size(); k++) {
            ApplyResult r = results.get(k);
            int i = validIndex.get(k);
            items[i] = new IngestReport.Item(i, r.employeeId(), r.version(), r.outcome(), r.reasonCode());
        }

        List<IngestReport.Item> all = List.of(items);
        return new IngestReport(events.size(), count(all, "APPLIED"), count(all, "VERSION_ONLY"),
                count(all, "STALE"), count(all, "DUPLICATE"), count(all, "CONFLICT"), count(all, "REJECTED"),
                count(all, "FAILED"), all, errors, warnings);
    }

    private HrChange toChange(String tenantId, HrEventDto e, List<FieldError> errors) {
        if (e == null) {
            errors.add(new FieldError("event", "REQUIRED", "event must not be null"));
            return null;
        }
        String type = e.type() == null ? "" : e.type().strip().toUpperCase(Locale.ROOT);
        String employeeId = e.employeeId() == null ? null : e.employeeId().strip();
        errors.addAll(validator.validateIdentity(employeeId, e.version()));
        switch (type) {
            case "CREATE", "UPDATE" -> {
                HrRecord record = new HrRecord(e.name(), e.email(), e.phone(), e.managerId(), e.officeCode(),
                        e.shiftCode());
                errors.addAll(validator.validateRecord(tenantId, record));
                return errors.isEmpty()
                        ? HrChange.upsert(tenantId, employeeId, e.version(), record, ChangeSource.API) : null;
            }
            case "DEACTIVATE" -> {
                return errors.isEmpty()
                        ? HrChange.deactivate(tenantId, employeeId, e.version(), ChangeSource.API) : null;
            }
            default -> {
                errors.add(new FieldError("type", "INVALID_TYPE", "type must be CREATE, UPDATE or DEACTIVATE"));
                return null;
            }
        }
    }

    private static String safeId(HrEventDto e) {
        if (e == null || e.employeeId() == null) return null;
        String id = e.employeeId().strip();
        return RecordValidator.EMPLOYEE_ID.matcher(id).matches() ? id : null;
    }

    private static int count(List<IngestReport.Item> items, String outcome) {
        return (int) items.stream().filter(i -> outcome.equals(i.outcome())).count();
    }
}
