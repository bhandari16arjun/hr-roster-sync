package com.moveinsync.rostersync.benchmark;

import com.moveinsync.rostersync.reconciliation.ReconciliationRun;
import com.moveinsync.rostersync.reconciliation.ReconciliationService;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.SnapshotCsv;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Diff a 200,000-row snapshot in under 60 seconds with bounded memory."
 * Run with:  mvn -Pbenchmark test      (the JVM is capped at -Xmx384m in that profile)
 */
@Tag("benchmark")
class SnapshotDiffBenchmarkTest extends IntegrationTest {

    private static final int N = 200_000;

    @Autowired
    ReconciliationService service;

    private ReconciliationRun run(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return service.reconcile(ACME, in);
        }
    }

    @Test
    void diff200kRows() throws Exception {
        Path dir = Files.createTempDirectory("bench");
        BenchmarkReport.line("");
        BenchmarkReport.line("## Nightly diff benchmark (" + N + " employees)");
        BenchmarkReport.line("Machine: " + BenchmarkReport.machine());

        // night 0: first full load (every row is new)
        Path night0 = dir.resolve("night0.csv");
        SnapshotCsv.writeFile(night0, i -> SnapshotCsv.line("E" + i, 1, "BLR-01", "S1"), N);
        long t = System.nanoTime();
        ReconciliationRun load = run(night0);
        long loadMs = (System.nanoTime() - t) / 1_000_000;
        assertThat(load.status()).isEqualTo("COMPLETED");
        assertThat(load.newCount()).isEqualTo(N);
        BenchmarkReport.line(String.format("- Initial load of %,d new employees: **%,d ms** total (compare %,d ms)",
                N, loadMs, load.compareMillis()));

        // night 1: 1%% changed (2,000), 0.5%% new (1,000), 1%% left (2,000), rest identical
        Path night1 = dir.resolve("night1.csv");
        SnapshotCsv.writeFile(night1, i -> {
            if (i < 2_000) return SnapshotCsv.line("E" + i, 2, "HYD-01", "S2");     // changed
            if (i >= N - 2_000) return null;                                          // left
            return SnapshotCsv.line("E" + i, 1, "BLR-01", "S1");                      // unchanged
        }, N);
        try (var w = Files.newBufferedWriter(night1, java.nio.file.StandardOpenOption.APPEND)) {
            for (int i = N; i < N + 1_000; i++) {
                w.write(SnapshotCsv.line("E" + i, 1, "BLR-02", "S1"));               // new
            }
        }
        System.gc();
        BenchmarkReport.resetPeakHeap();
        t = System.nanoTime();
        ReconciliationRun diff = run(night1);
        long diffMs = (System.nanoTime() - t) / 1_000_000;
        long peak = BenchmarkReport.peakHeapMb();

        assertThat(diff.status()).isEqualTo("COMPLETED");
        assertThat(diff.changedCount()).isEqualTo(2_000);
        assertThat(diff.newCount()).isEqualTo(1_000);
        assertThat(diff.missingCount()).isEqualTo(2_000);
        assertThat(diff.unchangedCount()).isEqualTo(N - 4_000);
        assertThat(diffMs).isLessThan(60_000);
        BenchmarkReport.line(String.format("- Nightly diff of %,d rows (2,000 changed, 1,000 new, 2,000 left): "
                        + "**%,d ms** end to end (parse+validate+spill, compare %,d ms, apply+deactivate); "
                        + "peak heap during run <= **%d MB** (JVM max %d MB)",
                N + 1_000 - 2_000, diffMs, diff.compareMillis(), peak, Runtime.getRuntime().maxMemory() / (1024 * 1024)));

        // night 2: the file is cut off at 120,000 rows -> safety brake
        Path cut = dir.resolve("cut.csv");
        SnapshotCsv.writeFile(cut, i -> SnapshotCsv.line("E" + i, 1, "BLR-01", "S1"), 120_000);
        t = System.nanoTime();
        ReconciliationRun braked = run(cut);
        long brakeMs = (System.nanoTime() - t) / 1_000_000;
        assertThat(braked.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'ACTIVE'")).isEqualTo(N - 2_000 + 1_000);
        BenchmarkReport.line(String.format("- Truncated file (120,000 rows): brake tripped in **%,d ms**, "
                + "missing=%,d, nobody deactivated. Reason: %s", brakeMs, braked.missingCount(), braked.brakeReason()));
    }
}
