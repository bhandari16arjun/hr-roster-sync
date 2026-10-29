package com.moveinsync.rostersync.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot crash injection. Arm a crash point; the next time the code reaches it, the "crash"
 * happens once and the point disarms itself.
 *
 * In the running app the crash is a real {@code Runtime.halt()} - the JVM dies instantly with no
 * shutdown hooks and no transaction cleanup, exactly like kill -9. Tests swap in a Crasher that
 * throws instead, so the same code path can be asserted in-process.
 */
@Component
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    @FunctionalInterface
    public interface Crasher {
        void crash(CrashPoint point);
    }

    public static final Crasher HALT_JVM = point -> Runtime.getRuntime().halt(137);

    private final Map<CrashPoint, Boolean> armed = new ConcurrentHashMap<>();
    private volatile Crasher crasher = HALT_JVM;

    public void arm(CrashPoint point) {
        armed.put(point, Boolean.TRUE);
        log.warn("fault injection armed point={}", point);
    }

    public void disarmAll() {
        armed.clear();
    }

    public void setCrasher(Crasher crasher) {
        this.crasher = crasher;
    }

    /** Called at each crash point. Does nothing unless that point is armed. */
    public void maybeCrash(CrashPoint point) {
        if (armed.remove(point) != null) {
            log.error("fault injection firing point={}", point);
            crasher.crash(point);
        }
    }
}
