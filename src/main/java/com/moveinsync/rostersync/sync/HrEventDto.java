package com.moveinsync.rostersync.sync;

/**
 * One event pushed by the HR system.
 *
 * type: CREATE | UPDATE | DEACTIVATE. CREATE/UPDATE carry the full HR record.
 * pickupPoint / specialAssistance are NOT HR fields: they are accepted only so that we can warn
 * HR that they were ignored (field ownership) instead of silently dropping them.
 */
public record HrEventDto(String type, String employeeId, Long version, String name, String email, String phone,
                         String managerId, String officeCode, String shiftCode,
                         String pickupPoint, String specialAssistance) {
}
