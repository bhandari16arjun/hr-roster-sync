package com.moveinsync.rostersync.domain;

/** Types of change events published (through the outbox) to downstream services. */
public enum ChangeEventType {
    EMPLOYEE_CREATED,
    EMPLOYEE_UPDATED,
    EMPLOYEE_REACTIVATED,
    EMPLOYEE_DEACTIVATED,
    TRANSPORT_FIELDS_UPDATED
}
