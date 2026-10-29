package com.moveinsync.rostersync.domain;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Splits employees into a fixed number of hash buckets. The nightly diff compares one bucket at a
 * time, so memory is bounded by (export size / BUCKET_COUNT) instead of the whole export.
 *
 * CRC32 is used (not String.hashCode) because it is stable across JVMs and languages, and the
 * bucket number is persisted in the employee table.
 */
public final class Buckets {

    public static final int BUCKET_COUNT = 64;

    private Buckets() {
    }

    public static int of(String employeeId) {
        CRC32 crc = new CRC32();
        crc.update(employeeId.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % BUCKET_COUNT);
    }
}
