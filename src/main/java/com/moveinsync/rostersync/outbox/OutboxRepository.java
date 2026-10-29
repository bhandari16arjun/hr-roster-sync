package com.moveinsync.rostersync.outbox;

import com.moveinsync.rostersync.domain.ChangeEventType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxEvent> MAPPER = (rs, i) -> new OutboxEvent(
            rs.getLong("id"), rs.getObject("event_id", UUID.class), rs.getString("tenant_id"),
            rs.getString("employee_id"), ChangeEventType.valueOf(rs.getString("event_type")),
            rs.getLong("hr_version"), rs.getString("payload"), rs.getInt("attempts"),
            rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called inside the business transaction: the event row commits or rolls back with the data change. */
    public void append(List<NewEvent> events, Instant now) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO outbox_event (event_id, tenant_id, employee_id, event_type, hr_version, payload,
                                          created_at, next_attempt_at)
                VALUES (:id, :t, :e, :type, :v, :payload, :now, :now)""",
                events.stream().map(ev -> new MapSqlParameterSource()
                        .addValue("id", ev.eventId()).addValue("t", ev.tenantId()).addValue("e", ev.employeeId())
                        .addValue("type", ev.type().name()).addValue("v", ev.hrVersion())
                        .addValue("payload", ev.payload()).addValue("now", Timestamp.from(now)))
                        .toArray(SqlParameterSource[]::new));
    }

    /**
     * Claims due events in insertion order. FOR UPDATE SKIP LOCKED lets several app instances run
     * the relay at the same time without publishing the same row concurrently.
     */
    public List<OutboxEvent> claimDue(int limit, Instant now) {
        return jdbc.query("""
                SELECT * FROM outbox_event
                WHERE status = 'PENDING' AND next_attempt_at <= :now
                ORDER BY id LIMIT :limit
                FOR UPDATE SKIP LOCKED""",
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("limit", limit), MAPPER);
    }

    public void markPublished(List<Long> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE outbox_event SET status = 'PUBLISHED', published_at = :now, attempts = attempts + 1 "
                + "WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids).addValue("now", Timestamp.from(now)));
    }

    public void markFailed(long id, int attempts, Instant nextAttemptAt, String error, boolean dead) {
        jdbc.update("""
                UPDATE outbox_event SET attempts = :a, next_attempt_at = :next, last_error = :err,
                       status = CASE WHEN :dead THEN 'DEAD' ELSE 'PENDING' END
                WHERE id = :id""",
                new MapSqlParameterSource("id", id).addValue("a", attempts)
                        .addValue("next", Timestamp.from(nextAttemptAt)).addValue("err", error)
                        .addValue("dead", dead));
    }

    public List<OutboxEvent> findDead(String tenantId, int limit) {
        return jdbc.query("SELECT * FROM outbox_event WHERE tenant_id = :t AND status = 'DEAD' ORDER BY id LIMIT :l",
                new MapSqlParameterSource("t", tenantId).addValue("l", limit), MAPPER);
    }

    public int replayDead(String tenantId, Instant now) {
        return jdbc.update("UPDATE outbox_event SET status = 'PENDING', attempts = 0, next_attempt_at = :now "
                        + "WHERE tenant_id = :t AND status = 'DEAD'",
                new MapSqlParameterSource("t", tenantId).addValue("now", Timestamp.from(now)));
    }

    public List<OutboxEvent> findForEmployee(String tenantId, String employeeId) {
        return jdbc.query("SELECT * FROM outbox_event WHERE tenant_id = :t AND employee_id = :e ORDER BY id",
                new MapSqlParameterSource("t", tenantId).addValue("e", employeeId), MAPPER);
    }

    /** Pending count and age of the oldest pending event (the relay lag), across tenants, for metrics. */
    public Stats stats(Instant now) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'DEAD')    AS dead,
                       min(created_at) FILTER (WHERE status = 'PENDING') AS oldest
                FROM outbox_event""", Map.of());
        long pending = ((Number) row.get("pending")).longValue();
        long dead = ((Number) row.get("dead")).longValue();
        Object oldest = row.get("oldest");
        double lagSeconds = 0;
        if (oldest instanceof Timestamp ts) {
            lagSeconds = Math.max(0, (now.toEpochMilli() - ts.toInstant().toEpochMilli()) / 1000.0);
        } else if (oldest instanceof java.time.OffsetDateTime odt) {
            lagSeconds = Math.max(0, (now.toEpochMilli() - odt.toInstant().toEpochMilli()) / 1000.0);
        }
        return new Stats(pending, dead, lagSeconds);
    }

    public record Stats(long pending, long dead, double oldestPendingAgeSeconds) {
    }

    public record NewEvent(UUID eventId, String tenantId, String employeeId, ChangeEventType type, long hrVersion,
                           String payload) {
    }
}
