package com.moveinsync.rostersync.domain;

public enum ChangeSource {
    /** Near-real-time push from the HR system. */
    API,
    /** A row of the nightly full export. */
    SNAPSHOT,
    /** Deactivation decided by the nightly reconciliation (employee missing from the export). */
    RECONCILIATION
}
