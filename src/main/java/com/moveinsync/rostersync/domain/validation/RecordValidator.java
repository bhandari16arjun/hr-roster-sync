package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.HrRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates one HR record at the boundary. Returns ALL problems for the record (not just the
 * first), so HR can fix a record in one go. Never throws for bad data: bad data is a normal,
 * expected input and must not fail the batch.
 */
public final class RecordValidator {

    public static final Pattern EMPLOYEE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    /** E.164-style: optional +, 10 to 15 digits, no leading zero. */
    private static final Pattern PHONE = Pattern.compile("^\\+?[1-9][0-9]{9,14}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern SHIFT = Pattern.compile("^[A-Za-z0-9_:-]{1,32}$");

    private final OfficeDirectory offices;

    public RecordValidator(OfficeDirectory offices) {
        this.offices = offices;
    }

    public List<FieldError> validateIdentity(String employeeId, Long version) {
        List<FieldError> errors = new ArrayList<>();
        if (employeeId == null || !EMPLOYEE_ID.matcher(employeeId).matches()) {
            errors.add(new FieldError("employeeId", "INVALID_EMPLOYEE_ID",
                    "employeeId must be 1-32 characters of letters, digits, '-' or '_'"));
        }
        if (version == null || version <= 0) {
            errors.add(new FieldError("version", "INVALID_VERSION", "version must be a positive integer"));
        }
        return errors;
    }

    public List<FieldError> validateRecord(String tenantId, HrRecord r) {
        List<FieldError> errors = new ArrayList<>();
        if (r.name() == null) {
            errors.add(new FieldError("name", "REQUIRED", "name is required"));
        } else if (r.name().length() > 128) {
            errors.add(new FieldError("name", "TOO_LONG", "name must be at most 128 characters"));
        }
        if (r.email() == null) {
            errors.add(new FieldError("email", "REQUIRED", "email is required"));
        } else if (r.email().length() > 254 || !EMAIL.matcher(r.email()).matches()) {
            errors.add(new FieldError("email", "INVALID_EMAIL", "email is not a valid address"));
        }
        if (r.phone() != null && !PHONE.matcher(r.phone()).matches()) {
            errors.add(new FieldError("phone", "INVALID_PHONE",
                    "phone must be 10-15 digits with an optional leading '+'"));
        }
        if (r.managerId() != null && !EMPLOYEE_ID.matcher(r.managerId()).matches()) {
            errors.add(new FieldError("managerId", "INVALID_MANAGER_ID", "managerId has an invalid format"));
        }
        if (r.officeCode() == null) {
            errors.add(new FieldError("officeCode", "REQUIRED", "officeCode is required"));
        } else if (!offices.exists(tenantId, r.officeCode())) {
            errors.add(new FieldError("officeCode", "UNKNOWN_OFFICE", "officeCode is not a known office for this client"));
        }
        if (r.shiftCode() != null && !SHIFT.matcher(r.shiftCode()).matches()) {
            errors.add(new FieldError("shiftCode", "INVALID_SHIFT", "shiftCode has an invalid format"));
        }
        return errors;
    }
}
