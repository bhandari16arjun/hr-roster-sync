package com.moveinsync.rostersync.reconciliation;

import org.apache.commons.csv.CSVFormat;

/**
 * The nightly export format: CSV with a header row
 *
 *   employeeId,version,name,email,phone,managerId,officeCode,shiftCode
 *
 * and an OPTIONAL last line "#TRAILER,<rowCount>". If the trailer is present and the number of
 * data rows read differs, the file is truncated and the safety brake trips.
 */
public final class SnapshotFormat {

    public static final String[] HEADER = {"employeeId", "version", "name", "email", "phone", "managerId",
            "officeCode", "shiftCode"};
    public static final String TRAILER_MARKER = "#TRAILER";

    /** For reading the uploaded file (header taken from the file, and required). */
    public static final CSVFormat READ = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(false)
            .build();

    /** For the internal bucket/plan spill files (no header). */
    public static final CSVFormat SPILL = CSVFormat.DEFAULT;

    private SnapshotFormat() {
    }
}
