package com.moveinsync.rostersync.ops;

/** Places in the code where a crash can be injected to prove the outbox guarantees. */
public enum CrashPoint {
    /** Inside the sync transaction, after the employee row is written but BEFORE the outbox row. */
    SYNC_BEFORE_OUTBOX_WRITE,
    /** The employee change and outbox row are committed; the relay dies before publishing. */
    RELAY_BEFORE_PUBLISH,
    /** The relay published the event but dies before marking it PUBLISHED (so it will publish again). */
    RELAY_AFTER_PUBLISH_BEFORE_MARK
}
