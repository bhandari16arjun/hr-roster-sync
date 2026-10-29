package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.domain.Buckets;
import com.moveinsync.rostersync.domain.HrRecord;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Temporary files of one reconciliation run, all under its work directory:
 *   bucket-00.csv .. bucket-63.csv   valid export rows, split by employee bucket
 *   plan.csv                         rows that must be applied (new / changed / version bump)
 *
 * Writing the export to disk by bucket is what keeps memory bounded: the compare step only ever
 * holds ONE bucket (about 1/64 of the file) in memory.
 */
public final class SpillFiles {

    private SpillFiles() {
    }

    public static Path bucketFile(Path workDir, int bucket) {
        return workDir.resolve(String.format("bucket-%02d.csv", bucket));
    }

    public static Path planFile(Path workDir) {
        return workDir.resolve("plan.csv");
    }

    /** Writes rows into 64 bucket files. */
    public static final class BucketWriter implements Closeable {
        private final CSVPrinter[] printers = new CSVPrinter[Buckets.BUCKET_COUNT];

        public BucketWriter(Path workDir) throws IOException {
            Files.createDirectories(workDir);
            for (int b = 0; b < printers.length; b++) {
                printers[b] = new CSVPrinter(Files.newBufferedWriter(bucketFile(workDir, b), StandardCharsets.UTF_8),
                        SnapshotFormat.SPILL);
            }
        }

        public void write(SnapshotRow row) throws IOException {
            SpillFiles.write(printers[Buckets.of(row.employeeId())], row);
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (CSVPrinter p : printers) {
                try {
                    if (p != null) p.close();
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) throw first;
        }
    }

    /** Appends rows to plan.csv. */
    public static final class PlanWriter implements Closeable {
        private final CSVPrinter printer;

        public PlanWriter(Path workDir) throws IOException {
            BufferedWriter w = Files.newBufferedWriter(planFile(workDir), StandardCharsets.UTF_8);
            this.printer = new CSVPrinter(w, SnapshotFormat.SPILL);
        }

        public void write(SnapshotRow row) throws IOException {
            SpillFiles.write(printer, row);
        }

        @Override
        public void close() throws IOException {
            printer.close();
        }
    }

    public static void readRows(Path file, Consumer<SnapshotRow> consumer) {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = SnapshotFormat.SPILL.parse(r)) {
            for (CSVRecord rec : parser) {
                consumer.accept(new SnapshotRow(Long.parseLong(rec.get(0)), rec.get(1), Long.parseLong(rec.get(2)),
                        new HrRecord(nullable(rec.get(3)), nullable(rec.get(4)), nullable(rec.get(5)),
                                nullable(rec.get(6)), nullable(rec.get(7)), nullable(rec.get(8)))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteQuietly(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(workDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup of temp files
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void write(CSVPrinter p, SnapshotRow row) throws IOException {
        HrRecord r = row.record();
        p.printRecord(row.rowNumber(), row.employeeId(), row.version(), e(r.name()), e(r.email()), e(r.phone()),
                e(r.managerId()), e(r.officeCode()), e(r.shiftCode()));
    }

    private static String e(String s) {
        return s == null ? "" : s;
    }

    private static String nullable(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
