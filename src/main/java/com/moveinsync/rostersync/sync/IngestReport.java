package com.moveinsync.rostersync.sync;

import java.util.List;

/** Per-batch report returned to the HR system. Contains IDs, codes and field names - never values. */
public record IngestReport(int received, int applied, int versionOnly, int stale, int duplicate, int conflict,
                           int rejected, int failed, List<Item> results, List<ItemError> errors,
                           List<ItemError> warnings) {

    public record Item(int index, String employeeId, Long version, String outcome, String reasonCode) {
    }

    public record ItemError(int index, String employeeId, String field, String code, String message) {
    }
}
