package com.moveinsync.rostersync.security;

/**
 * HR_SYSTEM       - the client's HR system (machine user): pushes events and nightly files.
 * TRANSPORT_ADMIN - the client's transport admin: owns pickup point / special assistance, manages bookings.
 * OPERATOR        - MoveInSync operator for that client: approves braked runs, watches the outbox.
 * EMPLOYEE        - an employee: may only read their own record.
 */
public enum Role {
    HR_SYSTEM,
    TRANSPORT_ADMIN,
    OPERATOR,
    EMPLOYEE
}
