-- ---------------------------------------------------------------------------
-- HR Roster Sync schema
-- Every table is keyed by tenant_id first: tenant isolation is enforced in SQL,
-- not only in the application.
-- ---------------------------------------------------------------------------

-- Offices known per tenant (used to validate office codes at the boundary).
CREATE TABLE office (
    tenant_id    VARCHAR(64)  NOT NULL,
    office_code  VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, office_code)
);

-- One row per employee. HR-owned and transport-owned columns live side by side,
-- but each owner has its own write path (see EmployeeMerger / TransportService).
CREATE TABLE employee (
    tenant_id             VARCHAR(64)  NOT NULL,
    employee_id           VARCHAR(32)  NOT NULL,
    bucket                SMALLINT     NOT NULL,           -- CRC32(employee_id) % 64, used by the nightly diff
    status                VARCHAR(16)  NOT NULL,           -- ACTIVE | INACTIVE
    hr_version            BIGINT       NOT NULL,           -- highest source version applied
    hr_hash               CHAR(64)     NOT NULL,           -- SHA-256 of the HR-owned fields
    -- HR-owned fields
    name                  VARCHAR(128),
    email                 VARCHAR(254),
    phone                 VARCHAR(20),
    manager_id            VARCHAR(32),
    office_code           VARCHAR(32),
    shift_code            VARCHAR(32),
    -- transport-admin-owned fields
    pickup_point          VARCHAR(256),
    special_assistance    VARCHAR(256),
    transport_updated_at  TIMESTAMPTZ,
    transport_updated_by  VARCHAR(64),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, employee_id)
);
CREATE INDEX idx_employee_bucket ON employee (tenant_id, bucket);

-- Transactional outbox: written in the SAME transaction as the employee change.
CREATE TABLE outbox_event (
    id               BIGSERIAL    PRIMARY KEY,
    event_id         UUID         NOT NULL UNIQUE,
    tenant_id        VARCHAR(64)  NOT NULL,
    employee_id      VARCHAR(32)  NOT NULL,
    event_type       VARCHAR(32)  NOT NULL,
    hr_version       BIGINT       NOT NULL,
    payload          TEXT         NOT NULL,                -- JSON, contains no personal data
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING | PUBLISHED | DEAD
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(256),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_event (next_attempt_at, id) WHERE status = 'PENDING';

-- Consumer-side dedup: an event id is processed at most once per consumer.
CREATE TABLE processed_event (
    consumer      VARCHAR(64) NOT NULL,
    event_id      UUID        NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- Cab bookings (minimal model, enough to show deactivation side effects).
CREATE TABLE booking (
    id             BIGSERIAL   PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    employee_id    VARCHAR(32) NOT NULL,
    trip_date      DATE        NOT NULL,
    seat_no        VARCHAR(8),
    status         VARCHAR(16) NOT NULL,                   -- CONFIRMED | CANCELLED
    cancel_reason  VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_employee ON booking (tenant_id, employee_id, trip_date);

-- Nightly reconciliation runs.
CREATE TABLE reconciliation_run (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    status           VARCHAR(24)  NOT NULL,   -- RUNNING | APPLYING | AWAITING_APPROVAL | COMPLETED | REJECTED | SUPERSEDED | FAILED
    file_rows        INT          NOT NULL DEFAULT 0,
    declared_rows    INT,
    invalid_rows     INT          NOT NULL DEFAULT 0,
    active_before    INT          NOT NULL DEFAULT 0,
    new_count        INT          NOT NULL DEFAULT 0,
    changed_count    INT          NOT NULL DEFAULT 0,
    unchanged_count  INT          NOT NULL DEFAULT 0,
    stale_count      INT          NOT NULL DEFAULT 0,
    missing_count    INT          NOT NULL DEFAULT 0,
    applied_count    INT          NOT NULL DEFAULT 0,
    deactivated_count INT         NOT NULL DEFAULT 0,
    brake_reason     VARCHAR(512),
    work_dir         VARCHAR(512),
    compare_millis   BIGINT,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    decided_by       VARCHAR(64),
    decided_at       TIMESTAMPTZ
);
CREATE INDEX idx_run_tenant ON reconciliation_run (tenant_id, started_at DESC);
-- at most one run per tenant may be actively running/applying
CREATE UNIQUE INDEX uq_run_active_per_tenant ON reconciliation_run (tenant_id)
    WHERE status IN ('RUNNING', 'APPLYING');

-- Employees that were ACTIVE in the store but absent from the export.
-- hr_version is captured at compare time so a newer API update is never overridden.
CREATE TABLE reconciliation_missing (
    run_id       UUID        NOT NULL,
    employee_id  VARCHAR(32) NOT NULL,
    hr_version   BIGINT      NOT NULL,
    PRIMARY KEY (run_id, employee_id)
);

-- Per-record error report (no field values are stored, only codes).
CREATE TABLE reconciliation_error (
    run_id       UUID        NOT NULL,
    row_number   INT         NOT NULL,
    employee_id  VARCHAR(32),
    field        VARCHAR(32) NOT NULL,
    code         VARCHAR(32) NOT NULL,
    PRIMARY KEY (run_id, row_number, field)
);
