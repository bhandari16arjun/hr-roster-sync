package com.moveinsync.rostersync.outbox;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Consumer-side idempotency: remembers which (consumer, eventId) pairs were already handled. */
@Repository
public class ProcessedEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProcessedEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time; false if the event was already processed (duplicate). */
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (:c, :e) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("c", consumer).addValue("e", eventId)) == 1;
    }
}
