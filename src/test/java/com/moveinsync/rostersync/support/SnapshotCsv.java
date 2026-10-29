package com.moveinsync.rostersync.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds nightly export CSVs for tests and benchmarks. */
public final class SnapshotCsv {

    public static final String HEADER = "employeeId,version,name,email,phone,managerId,officeCode,shiftCode\n";

    private final StringBuilder sb = new StringBuilder(HEADER);

    public SnapshotCsv row(String id, long version, String office, String shift) {
        sb.append(line(id, version, office, shift));
        return this;
    }

    public SnapshotCsv raw(String line) {
        sb.append(line).append('\n');
        return this;
    }

    public SnapshotCsv trailer(int rows) {
        sb.append("#TRAILER,").append(rows).append('\n');
        return this;
    }

    public InputStream stream() {
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String line(String id, long version, String office, String shift) {
        return id + "," + version + ",Person " + id + "," + id.toLowerCase() + "@acme.com,+9198" + digits(id)
                + ",M1," + office + "," + shift + "\n";
    }

    /** Writes a large export straight to disk (constant memory). */
    public static void writeFile(Path file, RowSource source, int rows) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(HEADER);
            for (int i = 0; i < rows; i++) {
                String l = source.line(i);
                if (l != null) {
                    w.write(l);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    public interface RowSource {
        String line(int i);
    }

    private static String digits(String id) {
        String d = id.replaceAll("[^0-9]", "");
        return String.format("%08d", d.isEmpty() ? 0 : Long.parseLong(d) % 100_000_000L);
    }
}
