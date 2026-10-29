package com.moveinsync.rostersync.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Small helpers for benchmark output: percentiles, peak heap, and a results file. */
final class BenchmarkReport {

    static final Path OUT = Path.of("target", "benchmark-results.md");

    private BenchmarkReport() {
    }

    static void line(String s) {
        System.out.println("[BENCH] " + s);
        try {
            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, s + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static double percentileMillis(long[] nanos, double p) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] / 1_000_000.0;
    }

    static void resetPeakHeap() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /** Sum of the peak usage of every heap pool since the last reset (an upper bound of the real peak). */
    static long peakHeapMb() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                sum += pool.getPeakUsage().getUsed();
            }
        }
        return sum / (1024 * 1024);
    }

    static String machine() {
        Runtime rt = Runtime.getRuntime();
        return String.format("%d vCPU, max heap %d MB, Java %s, %s %s", rt.availableProcessors(),
                rt.maxMemory() / (1024 * 1024), System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.arch"));
    }
}
