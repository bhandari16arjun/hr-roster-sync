package com.moveinsync.rostersync.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default publisher: hands each event to every interested in-process handler.
 * If a handler throws, publish() throws, the relay records the failure and retries later.
 */
@Component
public class InProcessEventBus implements ChangeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private final List<ChangeEventHandler> handlers;

    public InProcessEventBus(List<ChangeEventHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void publish(OutboxEvent event) {
        for (ChangeEventHandler h : handlers) {
            if (h.handles(event.type())) {
                h.handle(event);
            }
        }
        log.info("event published eventId={} type={} tenant={} employeeId={} version={}",
                event.eventId(), event.type(), event.tenantId(), event.employeeId(), event.hrVersion());
    }
}
