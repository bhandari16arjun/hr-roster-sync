package com.moveinsync.rostersync.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafetyBrakeTest {

    private final SafetyBrake brake = new SafetyBrake(0.30, 0.10);

    @Test
    void normalNightPasses() {
        // 200k active, 150 leavers
        assertThat(brake.evaluate(new SafetyBrake.Counts(200_000, 150, 199_900, 12, null)).tripped()).isFalse();
    }

    @Test
    void fileCutAt120kOf200kTrips() {
        SafetyBrake.Decision d = brake.evaluate(new SafetyBrake.Counts(200_000, 80_000, 120_000, 0, null));
        assertThat(d.tripped()).isTrue();
        assertThat(d.reason()).contains("80000 of 200000").contains("40.0%");
    }

    @Test
    void exactlyAtThresholdTrips() {
        assertThat(brake.evaluate(new SafetyBrake.Counts(1000, 300, 700, 0, null)).tripped()).isTrue();
        assertThat(brake.evaluate(new SafetyBrake.Counts(1000, 299, 701, 0, null)).tripped()).isFalse();
    }

    @Test
    void tooManyInvalidRowsTrips() {
        assertThat(brake.evaluate(new SafetyBrake.Counts(1000, 0, 1000, 150, null)).tripped()).isTrue();
    }

    @Test
    void trailerMismatchTrips() {
        SafetyBrake.Decision d = brake.evaluate(new SafetyBrake.Counts(1000, 5, 995, 0, 1000));
        assertThat(d.tripped()).isTrue();
        assertThat(d.reason()).contains("truncated");
    }

    @Test
    void firstEverLoadNeverTripsOnMissing() {
        assertThat(brake.evaluate(new SafetyBrake.Counts(0, 0, 5000, 0, 5000)).tripped()).isFalse();
    }

    @Test
    void emptyFileTripsWhenThereAreActiveEmployees() {
        assertThat(brake.evaluate(new SafetyBrake.Counts(1000, 1000, 0, 0, null)).tripped()).isTrue();
    }

    @Test
    void thresholdIsConfigurable() {
        SafetyBrake strict = new SafetyBrake(0.05, 0.10);
        assertThat(strict.evaluate(new SafetyBrake.Counts(1000, 60, 940, 0, null)).tripped()).isTrue();
        assertThatThrownBy(() -> new SafetyBrake(0, 0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
