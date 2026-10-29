package com.moveinsync.rostersync.outbox;

import com.moveinsync.rostersync.domain.ChangeEventType;

/** A downstream consumer of change events (e.g. the booking service). */
public interface ChangeEventHandler {

    /** Stable name, used as the dedup key together with the event id. */
    String name();

    boolean handles(ChangeEventType type);

    void handle(OutboxEvent event);
}
