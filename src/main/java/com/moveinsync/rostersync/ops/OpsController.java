package com.moveinsync.rostersync.ops;

import com.moveinsync.rostersync.common.error.BadRequestException;
import com.moveinsync.rostersync.common.error.NotFoundException;
import com.moveinsync.rostersync.config.AppProperties;
import com.moveinsync.rostersync.outbox.OutboxRelay;
import com.moveinsync.rostersync.outbox.OutboxRepository;
import com.moveinsync.rostersync.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Operator endpoints (role OPERATOR): outbox health, dead-letter replay, and demo fault injection. */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final OutboxRelay relay;
    private final OutboxRepository outbox;
    private final FaultInjector faults;
    private final AppProperties props;
    private final Clock clock;

    public OpsController(OutboxRelay relay, OutboxRepository outbox, FaultInjector faults, AppProperties props,
                         Clock clock) {
        this.relay = relay;
        this.outbox = outbox;
        this.faults = faults;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/outbox")
    public OutboxRepository.Stats outboxStats() {
        return relay.stats();
    }

    @GetMapping("/outbox/dead")
    public List<Map<String, Object>> dead() {
        return outbox.findDead(CurrentUser.tenantId(), 100).stream()
                .map(e -> Map.<String, Object>of("eventId", e.eventId(), "type", e.type(), "employeeId",
                        e.employeeId(), "attempts", e.attempts()))
                .toList();
    }

    @PostMapping("/outbox/dead/replay")
    public Map<String, Object> replayDead() {
        return Map.of("requeued", outbox.replayDead(CurrentUser.tenantId(), clock.instant()));
    }

    public record FaultRequest(String point) {
    }

    /**
     * DEMO ONLY (app.fault.enabled=true): arms a one-shot crash. The next time the code reaches that
     * point the JVM halts like kill -9; docker compose restarts it and the outbox recovers.
     */
    @PostMapping("/faults")
    public Map<String, Object> arm(@RequestBody FaultRequest body) {
        if (!props.fault().enabled()) {
            throw new NotFoundException("endpoint");
        }
        CrashPoint point;
        try {
            point = CrashPoint.valueOf(body.point());
        } catch (RuntimeException e) {
            throw new BadRequestException("UNKNOWN_CRASH_POINT", "point must be one of "
                    + List.of(CrashPoint.values()));
        }
        faults.arm(point);
        return Map.of("armed", point.name());
    }
}
