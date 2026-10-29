package com.moveinsync.rostersync.outbox;

import com.moveinsync.rostersync.domain.ChangeEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * A change event as stored in the outbox table and as delivered to consumers.
 * The payload is JSON with IDs, version, office/shift codes and changed field NAMES - no personal
 * data. Consumers that need a name or phone number read it through the authorised API.
 */
public record OutboxEvent(long id, UUID eventId, String tenantId, String employeeId, ChangeEventType type,
                          long hrVersion, String payload, int attempts, Instant createdAt) {
}
