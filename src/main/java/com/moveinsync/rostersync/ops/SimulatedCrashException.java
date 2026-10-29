package com.moveinsync.rostersync.ops;

/** Thrown by the test Crasher to simulate a process dying at a crash point. */
public class SimulatedCrashException extends RuntimeException {
    public SimulatedCrashException(CrashPoint point) {
        super("simulated crash at " + point);
    }
}
