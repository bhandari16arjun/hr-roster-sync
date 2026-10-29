package com.moveinsync.rostersync.outbox;

/**
 * Where outbox events go. The default implementation delivers to in-process handlers; a Kafka or
 * SNS implementation would replace it without touching the outbox or the sync logic.
 * Delivery is AT-LEAST-ONCE: implementations may see the same event twice and consumers must dedup.
 */
public interface ChangeEventPublisher {
    void publish(OutboxEvent event);
}
