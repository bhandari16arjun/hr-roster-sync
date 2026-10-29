package com.moveinsync.rostersync.domain;

/**
 * Decides whether a nightly export looks broken. If it does, the run stops and NOBODY is
 * deactivated until an operator approves.
 *
 * Trips when either:
 *   - missing / activeBefore >= maxMissingRatio   (e.g. file cut off at 120k of 200k rows)
 *   - invalid / fileRows     >= maxInvalidRatio   (e.g. a column shifted and every row is garbage)
 *   - the file declares a row count in its trailer and the real count is different (truncation)
 */
public final class SafetyBrake {

    private final double maxMissingRatio;
    private final double maxInvalidRatio;

    public SafetyBrake(double maxMissingRatio, double maxInvalidRatio) {
        if (maxMissingRatio <= 0 || maxMissingRatio > 1) {
            throw new IllegalArgumentException("maxMissingRatio must be in (0, 1]");
        }
        if (maxInvalidRatio <= 0 || maxInvalidRatio > 1) {
            throw new IllegalArgumentException("maxInvalidRatio must be in (0, 1]");
        }
        this.maxMissingRatio = maxMissingRatio;
        this.maxInvalidRatio = maxInvalidRatio;
    }

    public Decision evaluate(Counts c) {
        if (c.declaredRows() != null && c.declaredRows() != c.fileRows()) {
            return Decision.trip(String.format(
                    "File trailer declares %d rows but %d rows were read: the file looks truncated",
                    c.declaredRows(), c.fileRows()));
        }
        if (c.activeBefore() > 0) {
            double missingRatio = (double) c.missing() / c.activeBefore();
            if (missingRatio >= maxMissingRatio) {
                return Decision.trip(String.format(
                        "%d of %d active employees (%.1f%%) are missing from the export; threshold is %.1f%%",
                        c.missing(), c.activeBefore(), missingRatio * 100, maxMissingRatio * 100));
            }
        }
        if (c.fileRows() > 0) {
            double invalidRatio = (double) c.invalid() / c.fileRows();
            if (invalidRatio >= maxInvalidRatio) {
                return Decision.trip(String.format(
                        "%d of %d rows (%.1f%%) are invalid; threshold is %.1f%%",
                        c.invalid(), c.fileRows(), invalidRatio * 100, maxInvalidRatio * 100));
            }
        }
        return Decision.pass();
    }

    /** Inputs to the brake, all counts from the compare phase. */
    public record Counts(int activeBefore, int missing, int fileRows, int invalid, Integer declaredRows) {
    }

    public record Decision(boolean tripped, String reason) {
        static Decision trip(String reason) {
            return new Decision(true, reason);
        }

        static Decision pass() {
            return new Decision(false, null);
        }
    }
}
