package com.moveinsync.rostersync.benchmark;

import com.moveinsync.rostersync.domain.HrChange;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.sync.EmployeeSyncService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throughput and p50/p99 latency of the real-time apply path (validate -> lock -> merge -> write
 * employee + outbox -> commit) against a real PostgreSQL, with concurrent writers.
 * Measured at the service layer (no HTTP), so the numbers are the cost of the sync logic + DB.
 */
@Tag("benchmark")
class IngestLatencyBenchmarkTest extends IntegrationTest {

    @Autowired
    EmployeeSyncService sync;

    @Test
    void singleEventLatencyAndThroughput() throws Exception {
        int threads = 8;
        int perThread = 1_500;
        int employees = 5_000;
        // warm-up
        for (int i = 0; i < 500; i++) {
            sync.applyOne(upsert("W" + i, 1, "BLR-01", "S1"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<long[]>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                long[] lat = new long[perThread];
                for (int i = 0; i < perThread; i++) {
                    String id = "E" + ThreadLocalRandom.current().nextInt(employees);
                    long version = (long) thread * perThread + i + 1; // mostly increasing, sometimes stale
                    HrChange c = upsert(id, version, i % 2 == 0 ? "BLR-01" : "HYD-01", "S" + (i % 5));
                    long s = System.nanoTime();
                    sync.applyOne(c);
                    lat[i] = System.nanoTime() - s;
                }
                return lat;
            }));
        }
        long[] all = new long[threads * perThread];
        int k = 0;
        for (Future<long[]> f : futures) {
            for (long l : f.get(5, TimeUnit.MINUTES)) {
                all[k++] = l;
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        double throughput = all.length * 1000.0 / elapsedMs;
        BenchmarkReport.line("");
        BenchmarkReport.line("## Real-time apply benchmark (" + threads + " concurrent writers, " + employees
                + " employees, single-event transactions)");
        BenchmarkReport.line("Machine: " + BenchmarkReport.machine());
        BenchmarkReport.line(String.format("- %,d events in %,d ms -> **%,.0f events/s**; latency p50 **%.2f ms**, "
                        + "p99 **%.2f ms**, max %.2f ms",
                all.length, elapsedMs, throughput, BenchmarkReport.percentileMillis(all, 50),
                BenchmarkReport.percentileMillis(all, 99), BenchmarkReport.percentileMillis(all, 100)));
        assertThat(throughput).isGreaterThan(100);
    }

    @Test
    void batchedThroughput() {
        int batches = 100;
        int size = 500;
        long[] lat = new long[batches];
        long start = System.nanoTime();
        for (int b = 0; b < batches; b++) {
            List<HrChange> batch = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                batch.add(upsert("B" + (b * size + i), 1, "BLR-01", "S1"));
            }
            long s = System.nanoTime();
            sync.apply(ACME, batch);
            lat[b] = System.nanoTime() - s;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        BenchmarkReport.line(String.format("- Batched creates (%d x %d): **%,.0f events/s**; per-batch p50 %.1f ms, "
                        + "p99 %.1f ms", batches, size, batches * size * 1000.0 / elapsedMs,
                BenchmarkReport.percentileMillis(lat, 50), BenchmarkReport.percentileMillis(lat, 99)));
    }
}
