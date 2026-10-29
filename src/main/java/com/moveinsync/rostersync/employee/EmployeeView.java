package com.moveinsync.rostersync.employee;

import com.moveinsync.rostersync.domain.EmployeeState;
import com.moveinsync.rostersync.domain.HrRecord;

/** API representation of an employee (returned only to authorised callers of the same tenant). */
public record EmployeeView(String employeeId, String status, long hrVersion, String name, String email,
                           String phone, String managerId, String officeCode, String shiftCode,
                           String pickupPoint, String specialAssistance) {

    public static EmployeeView of(EmployeeState s) {
        HrRecord r = s.hrRecord();
        return new EmployeeView(s.employeeId(), s.status().name(), s.hrVersion(),
                r == null ? null : r.name(), r == null ? null : r.email(), r == null ? null : r.phone(),
                r == null ? null : r.managerId(), r == null ? null : r.officeCode(), r == null ? null : r.shiftCode(),
                s.transport().pickupPoint(), s.transport().specialAssistance());
    }
}
