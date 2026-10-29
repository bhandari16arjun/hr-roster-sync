package com.moveinsync.rostersync.sync;

/**
 * What happened to one incoming change.
 * outcome: APPLIED | VERSION_ONLY | STALE | DUPLICATE | CONFLICT | FAILED
 */
public record ApplyResult(String employeeId, long version, String outcome, String reasonCode) {
}
